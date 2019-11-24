package org.alephium.flow.core

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core.validation._
import org.alephium.flow.io.IOError
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object BlockChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            cliqueManager: ActorRef,
            flowHandler: ActorRef)(implicit config: PlatformProfile): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, cliqueManager, flowHandler))

  sealed trait Command
  case class AddBlocks(blocks: AVector[Block], origin: DataOrigin) extends Command
  case class AddPendingBlock(block: Block, origin: DataOrigin)     extends Command
}

class BlockChainHandler(val blockFlow: BlockFlow,
                        val chainIndex: ChainIndex,
                        cliqueManager: ActorRef,
                        flowHandler: ActorRef)(implicit val config: PlatformProfile)
    extends BaseActor
    with ChainHandlerLogger {
  import BlockChainHandler._

  val chain: BlockPool = blockFlow.getBlockChain(chainIndex)

  override def receive: Receive = {
    case AddBlocks(blocks, origin)      => handleBlocks(blocks, origin)
    case AddPendingBlock(block, origin) => handlePendingBlock(block, origin)
  }

  def handlePendingBlock(block: Block, origin: DataOrigin): Unit = {
    assert(!blockFlow.contains(block))
    val validationResult = origin match {
      case DataOrigin.LocalMining => Right(ValidBlock)
      case _: DataOrigin.Remote   => Validation.validateAfterDependencies(block, blockFlow)
    }
    validationResult match {
      case Left(e)                      => handleIoError(e)
      case Right(x: InvalidBlockStatus) => handleInvalidBlock(x)
      case Right(_: ValidBlock.type)    => handleValidBlock(block, origin)
    }
  }

  def handleBlocks(blocks: AVector[Block], origin: DataOrigin): Unit = {
    assert(Validation.checkSubtreeOfDAG(blocks))
    if (Validation.checkHasParent(blocks.head, blockFlow)) {
      blocks.foreach(handleBlock(_, origin))
    } else {
      log.warning(s"parent block is not included yet, might be DoS")
    }
  }

  def handleBlock(block: Block, origin: DataOrigin): Unit = {
    if (blockFlow.contains(block)) {
      // TODO: anti-DoS
      log.debug(s"Block for ${block.chainIndex} already exists")
    } else {
      Validation.validate(block, blockFlow, origin.isSyncing) match {
        case Left(e)                      => handleIoError(e)
        case Right(MissingDeps(hashes))   => handleMissingDeps(block, hashes, origin)
        case Right(x: InvalidBlockStatus) => handleInvalidBlock(x)
        case Right(_: ValidBlock.type)    => handleValidBlock(block, origin)
      }
    }
  }

  def handleValidBlock(block: Block, origin: DataOrigin): Unit = {
    logInfo(block.header)
    if (!origin.isSyncing) {
      broadcast(block, origin)
    }
    flowHandler.tell(FlowHandler.AddBlock(block, origin), sender())
  }

  def handleIoError(e: IOError): Unit = {
    log.debug(s"IO failed in block validation: ${e.toString}")
  }

  def handleMissingDeps(block: Block, hashes: AVector[Keccak256], origin: DataOrigin): Unit = {
    log.debug(s"""Missing depes: ${Utils.show(hashes)}""")
    val missings = scala.collection.mutable.HashSet(hashes.toArray: _*)
    flowHandler ! FlowHandler.PendingBlock(block, missings, origin, sender(), self)
  }

  def handleInvalidBlock(status: InvalidBlockStatus): Unit = {
    log.debug(s"Failed in block validation: $status")
  }

  def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = Message.serialize(SendBlocks(AVector(block)))
    val headerMessage = Message.serialize(SendHeaders(AVector(block.header)))
    if (config.brokerInfo.contains(block.chainIndex.from)) {
      cliqueManager ! CliqueManager.BroadCastBlock(block, blockMessage, headerMessage, origin)
    }
  }
}
