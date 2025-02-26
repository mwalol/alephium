// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import org.alephium.api.model._
import org.alephium.api.model.Output.Contract
import org.alephium.json.Json._
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.model.Address
import org.alephium.util._
import org.alephium.wallet.api.model._

class VotingTest extends AlephiumActorSpec {
  it should "test the voting pipeline" in new VotingFixture {
    val admin  = wallets.head
    val voters = wallets.tail
    val ContractRef(contractId, contractAddress, contractCode) =
      deployContract(admin, voters, U256.unsafe(voters.size))
    checkState(0, 0, false, false)

    allocateTokens(admin, voters, contractId.toHexString, contractCode)
    checkState(0, 0, false, true)

    val nbYes = voters.size - 1
    val nbNo  = voters.size - nbYes
    voters.take(nbYes).foreach(wallet => vote(wallet, contractId.toHexString, true, contractCode))
    voters.drop(nbYes).foreach(wallet => vote(wallet, contractId.toHexString, false, contractCode))
    checkState(nbYes, nbNo, false, true)

    close(admin, contractId.toHexString, contractCode)
    checkState(nbYes, nbNo, true, true)

    clique.selfClique().nodes.foreach { peer =>
      request[Boolean](stopMining, peer.restPort) is true
    }
    clique.stop()

    def checkState(nbYes: Int, nbNo: Int, isClosed: Boolean, isInitialized: Boolean) = {
      val contractState =
        request[ContractStateResult](
          getContractState(contractAddress.toBase58, activeAddressesGroup),
          restPort
        )
      contractState.fields.get(0).get is Val.U256(U256.unsafe(nbYes))
      contractState.fields.get(1).get is Val.U256(U256.unsafe(nbNo))
      contractState.fields.get(2).get is Val.Bool(isClosed)
      contractState.fields.get(3).get is Val.Bool(isInitialized)
      contractState.fields.get(4).get is Val.Address(Address.fromBase58(admin.activeAddress).get)
      contractState.fields.drop(5) is AVector.from[Val](
        voters.map(v => Val.Address(Address.fromBase58(v.activeAddress).get))
      )
    }
  }
}

trait VotingFixture extends WalletFixture {
  def deployContract(admin: Wallet, voters: Seq[Wallet], tokenAmount: U256): ContractRef = {
    val allocationTransfers = voters.zipWithIndex
      .map { case (_, i) =>
        s"""
          |transferAlph!(admin, voters[$i], $utxoFee)
          |transferTokenFromSelf!(voters[$i], selfTokenId!(), 1)""".stripMargin
      }
      .mkString("\n")
    // scalastyle:off no.equal
    val votingContract = s"""
        |TxContract Voting(
        |  mut yes: U256,
        |  mut no: U256,
        |  mut isClosed: Bool,
        |  mut initialized: Bool,
        |  admin: Address,
        |  voters: [Address; ${voters.size}]
        |) {
        |  pub payable fn allocateTokens() -> () {
        |     assert!(initialized == false)
        |     assert!(txCaller!(txCallerSize!() - 1) == admin)
        |     ${allocationTransfers}
        |     yes = 0
        |     no = 0
        |     initialized = true
        |  }
        |
        |  pub payable fn vote(choice: Bool, voter: Address) -> () {
        |    assert!(initialized == true && isClosed == false)
        |    transferAlph!(voter, admin, $utxoFee)
        |    transferTokenToSelf!(voter, selfTokenId!(), 1)
        |    if (choice == true) {
        |       yes = yes + 1
        |    } else {
        |       no = no + 1
        |    }
        |  }
        |
        |   pub fn close() -> () {
        |     assert!(initialized == true && isClosed == false)
        |     assert!(txCaller!(txCallerSize!() - 1) == admin)
        |     isClosed = true
        |   }
        | }
      """.stripMargin
    // scalastyle:on no.equal
    val votersList: String =
      voters.map(wallet => s"@${wallet.activeAddress}").mkString(",")
    val state = s"[ 0, 0, false, false, @${admin.activeAddress}, [${votersList}]]"
    contract(admin, votingContract, Some(state), Some(tokenAmount))
  }

  def allocateTokens(
      adminWallet: Wallet,
      votersWallets: Seq[Wallet],
      contractId: String,
      contractCode: String
  ): TxResult = {
    val allocationScript = s"""
        |TxScript TokenAllocation {
        |    pub payable fn main() -> () {
        |      let voting = Voting(#${contractId})
        |      let caller = txCaller!(0)
        |      approveAlph!(caller, $utxoFee * ${votersWallets.size})
        |      voting.allocateTokens()
        |    }
        |}
        $contractCode
      """.stripMargin
    script(adminWallet.publicKey.toHexString, allocationScript, adminWallet.creation.walletName)
  }

  def vote(
      voterWallet: Wallet,
      contractId: String,
      choice: Boolean,
      contractCode: String
  ): TxResult = {
    val votingScript = s"""
      |TxScript VotingScript {
      |  pub payable fn main() -> () {
      |    let caller = txCaller!(txCallerSize!() - 1)
      |    approveToken!(caller, #${contractId}, 1)
      |    let voting = Voting(#${contractId})
      |    approveAlph!(caller, $utxoFee)
      |    voting.vote($choice, caller)
      |  }
      |}
      $contractCode
      """.stripMargin
    script(voterWallet.publicKey.toHexString, votingScript, voterWallet.creation.walletName)
  }

  def close(adminWallet: Wallet, contractId: String, contractCode: String): TxResult = {
    val closingScript = s"""
      |TxScript ClosingScript {
      |  pub payable fn main() -> () {
      |    let voting = Voting(#${contractId})
      |    voting.close()
      |  }
      |}
      $contractCode
      """.stripMargin
    script(adminWallet.publicKey.toHexString, closingScript, adminWallet.creation.walletName)
  }
}

trait WalletFixture extends CliqueFixture {
  override val configValues = Map(("alephium.broker.broker-num", 1))
  val clique                = bootClique(1)
  val activeAddressesGroup  = 0
  val genesisWalletName     = "genesis-wallet"
  def submitTx(unsignedTx: String, txId: Hash, walletName: String): TxResult = {
    val signature =
      request[Sign.Result](sign(walletName, s"${txId.toHexString}"), restPort).signature
    val tx = request[TxResult](
      submitTransaction(s"""
          {
            "unsignedTx": "$unsignedTx",
            "signature":"${signature.toHexString}"
          }"""),
      restPort
    )
    confirmTx(tx, restPort)
    tx
  }

  def contract(
      wallet: Wallet,
      code: String,
      state: Option[String],
      issueTokenAmount: Option[U256]
  ): ContractRef = {
    val compileResult = request[CompileResult](compileContract(code), restPort)
    val buildResult = request[BuildContractResult](
      buildContract(
        fromPublicKey = wallet.publicKey.toHexString,
        code = compileResult.code,
        state = state,
        issueTokenAmount = issueTokenAmount
      ),
      restPort
    )
    val txResult = submitTx(buildResult.unsignedTx, buildResult.hash, wallet.creation.walletName)
    val Confirmed(blockHash, _, _, _, _) =
      request[TxStatus](getTransactionStatus(txResult), restPort)
    val block = request[BlockEntry](getBlock(blockHash.toHexString), restPort)

    // scalastyle:off no.equal
    val tx: Tx = block.transactions.find(_.id == txResult.txId).get
    // scalastyle:on no.equal

    val Contract(_, contractAddress, _) = tx.outputs
      .find(output =>
        output match {
          case Contract(_, _, _) => true
          case _                 => false
        }
      )
      .get
    ContractRef(buildResult.contractId, contractAddress, code)
  }

  def script(publicKey: String, code: String, walletName: String) = {
    val compileResult = request[CompileResult](compileScript(code), restPort)
    val buildResult = request[BuildScriptResult](
      buildScript(
        fromPublicKey = publicKey,
        code = compileResult.code
      ),
      restPort
    )
    submitTx(buildResult.unsignedTx, buildResult.hash, walletName)
  }

  def createWallets(nWallets: Int, restPort: Int, walletsBalance: U256): Seq[Wallet] = {
    request[WalletRestore.Result](restoreWallet(password, mnemonic, genesisWalletName), restPort)
    unitRequest(unlockWallet(password, genesisWalletName), restPort)
    val walletsCreation: IndexedSeq[WalletCreation] =
      (1 to nWallets).map(i => WalletCreation("password", s"walletName-$i", isMiner = Some(true)))
    val walletsCreationResult: IndexedSeq[WalletCreation.Result] =
      walletsCreation.map(walletCreation =>
        request[WalletCreation.Result](
          createWallet(
            walletCreation.password,
            walletCreation.walletName,
            walletCreation.isMiner.get
          ),
          restPort
        )
      )
    walletsCreation.zip(walletsCreationResult).map {
      case (walletCreation, walletCreationResult) => {
        import org.alephium.api.UtilJson._
        unitRequest(unlockWallet(walletCreation.password, walletCreation.walletName), restPort)
        val addresses = request[AVector[MinerAddressesInfo]](
          getMinerAddresses(walletCreation.walletName),
          restPort
        )
        // scalastyle:off no.equal
        val newActiveAddress =
          addresses.head.addresses.toIterable
            .find(_.group == activeAddressesGroup)
            .get
            .address
            .toBase58
        // scalastyle:on no.equal
        unitRequest(
          postWalletChangeActiveAddress(walletCreation.walletName, newActiveAddress),
          restPort
        )
        val txResult = request[Transfer.Result](
          transferWallet(genesisWalletName, newActiveAddress, walletsBalance),
          restPort
        )
        confirmTx(txResult, restPort)
        val pubKey = request[AddressInfo](
          getAddressInfo(walletCreation.walletName, newActiveAddress),
          restPort
        ).publicKey
        Wallet(walletCreation, walletCreationResult, newActiveAddress, pubKey)
      }
    }
  }

  clique.start()

  val restPort = clique.masterRestPort
  request[Balance](getBalance(address), restPort) is initialBalance

  startWS(defaultWsMasterPort)
  clique.selfClique().nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }

  val nWallets       = 5
  val walletsBalance = ALPH.alph(1000)
  val wallets        = createWallets(nWallets, restPort, walletsBalance)
  wallets.foreach(wallet =>
    request[Balance](getBalance(wallet.activeAddress), restPort).balance.value is walletsBalance
  )
  val utxoFee = "50000000000000"
}

final case class ContractRef(contractId: Hash, contractAddress: Address, code: String)
final case class Wallet(
    creation: WalletCreation,
    result: WalletCreation.Result,
    activeAddress: String,
    publicKey: PublicKey
)
