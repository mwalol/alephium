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

package org.alephium.flow.core

import scala.language.implicitConversions

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.crypto.{ED25519, ED25519Signature, SecP256K1, SecP256K1Signature}
import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.MemPool.AddedToSharedPool
import org.alephium.flow.validation.{TxScriptExeFailed, TxValidation}
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.{deserialize, serialize, Serde}
import org.alephium.util._

// scalastyle:off file.size.limit
class VMSpec extends AlephiumSpec {
  implicit def gasBox(n: Int): GasBox = GasBox.unsafe(n)

  it should "not start with private function" in new ContractFixture {
    val input =
      s"""
         |TxScript Foo {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val script      = Compiler.compileTxScript(input).rightValue
    val errorScript = StatefulScript.unsafe(AVector(script.methods.head.copy(isPublic = false)))
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, errorScript)).getMessage is
      s"Right(TxScriptExeFailed($ExternalPrivateMethodCall))"
  }

  it should "overflow frame stack" in new FlowFixture {
    val input =
      s"""
         |TxScript Foo {
         |  pub fn main() -> () {
         |    foo(${frameStackMaxSize - 1})
         |  }
         |
         |  fn foo(n: U256) -> () {
         |    if (n > 0) {
         |      foo(n - 1)
         |    }
         |  }
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(input).rightValue

    val chainIndex = ChainIndex.unsafe(0, 0)
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script)).getMessage is
      s"Right(TxScriptExeFailed($OutOfGas))"
    intercept[AssertionError](
      simpleScript(blockFlow, chainIndex, script, gas = 400000)
    ).getMessage is
      s"Right(TxScriptExeFailed($StackOverflow))"
  }

  trait CallFixture extends FlowFixture {
    def access: String

    lazy val input0 =
      s"""
         |TxContract Foo(mut x: U256) {
         |  $access fn add(a: U256) -> () {
         |    x = x + a
         |    if (a > 0) {
         |      add(a - 1)
         |    }
         |    return
         |  }
         |}
         |""".stripMargin
    lazy val script0      = Compiler.compileContract(input0).rightValue
    lazy val initialState = AVector[Val](Val.U256(U256.Zero))

    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val fromLockup = getGenesisLockupScript(chainIndex)
    lazy val txScript0  = contractCreation(script0, initialState, fromLockup, ALPH.alph(1))
    lazy val block0     = payableCall(blockFlow, chainIndex, txScript0)
    lazy val contractOutputRef0 =
      TxOutputRef.unsafe(block0.transactions.head, 0).asInstanceOf[ContractOutputRef]
    lazy val contractKey0 = contractOutputRef0.key

    lazy val input1 =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn add(a: U256) -> () {
         |    x = x + a
         |    if (a > 0) {
         |      add(a - 1)
         |    }
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  pub fn call() -> () {
         |    let foo = Foo(#${contractKey0.toHexString})
         |    foo.add(4)
         |    return
         |  }
         |}
         |""".stripMargin
  }

  it should "not call external private function" in new CallFixture {
    val access: String = ""

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1 = Compiler.compileTxScript(input1, 1).rightValue
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script1)).getMessage is
      s"Right(TxScriptExeFailed($ExternalPrivateMethodCall))"
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "pub"

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1   = Compiler.compileTxScript(input1, 1).rightValue
    val newState1 = AVector[Val](Val.U256(U256.unsafe(10)))
    val block1    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractKey0, newState1, contractOutputRef0, numAssets = 4)

    val newState2 = AVector[Val](Val.U256(U256.unsafe(20)))
    val block2    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractKey0, newState2, contractOutputRef0, numAssets = 6)
  }

  trait ContractFixture extends FlowFixture {
    val chainIndex     = ChainIndex.unsafe(0, 0)
    val genesisLockup  = getGenesisLockupScript(chainIndex)
    val genesisAddress = Address.Asset(genesisLockup)

    def createContract(
        input: String,
        initialState: AVector[Val],
        tokenAmount: Option[U256] = None,
        initialAlphAmount: U256 = dustUtxoAmount
    ): ContractOutputRef = {
      val contract = Compiler.compileContract(input).rightValue
      val txScript =
        contractCreation(contract, initialState, genesisLockup, initialAlphAmount, tokenAmount)
      val block = payableCall(blockFlow, chainIndex, txScript)

      val contractOutputRef =
        TxOutputRef.unsafe(block.transactions.head, 0).asInstanceOf[ContractOutputRef]

      deserialize[StatefulContract.HalfDecoded](serialize(contract.toHalfDecoded())).rightValue
        .toContract() isE contract
      addAndCheck(blockFlow, block)
      contractOutputRef
    }

    def createContractAndCheckState(
        input: String,
        numAssets: Int,
        numContracts: Int,
        initialState: AVector[Val] = AVector[Val](Val.U256(U256.Zero)),
        tokenAmount: Option[U256] = None,
        initialAlphAmount: U256 = dustUtxoAmount
    ): ContractOutputRef = {
      val contractOutputRef = createContract(input, initialState, tokenAmount, initialAlphAmount)

      val contractKey = contractOutputRef.key
      checkState(
        blockFlow,
        chainIndex,
        contractKey,
        initialState,
        contractOutputRef,
        numAssets,
        numContracts
      )

      contractOutputRef
    }

    def callTxScript(input: String): Unit = {
      val script = Compiler.compileTxScript(input).rightValue
      val block =
        if (script.entryMethod.isPayable) {
          payableCall(blockFlow, chainIndex, script)
        } else {
          simpleScript(blockFlow, chainIndex, script)
        }
      addAndCheck(blockFlow, block)
    }

    def callTxScriptMulti(input: Int => String): Block = {
      val block0 = transfer(blockFlow, chainIndex, numReceivers = 10)
      addAndCheck(blockFlow, block0)
      val newAddresses = block0.nonCoinbase.head.unsigned.fixedOutputs.init.map(_.lockupScript)
      val scripts = AVector.tabulate(newAddresses.length) { index =>
        Compiler.compileTxScript(input(index)).fold(throw _, identity)
      }
      val block1 = simpleScriptMulti(blockFlow, chainIndex, newAddresses, scripts)
      addAndCheck(blockFlow, block1)
      block1
    }

    def testSimpleScript(main: String) = {
      val script = Compiler.compileTxScript(main).rightValue
      val block  = simpleScript(blockFlow, chainIndex, script)
      addAndCheck(blockFlow, block)
    }

    def failSimpleScript(main: String, failure: ExeFailure) = {
      val script = Compiler.compileTxScript(main).rightValue
      intercept[AssertionError](simpleScript(blockFlow, chainIndex, script)).getMessage is
        s"Right(TxScriptExeFailed($failure))"
    }

    def fail(blockFlow: BlockFlow, block: Block, failure: ExeFailure): Assertion = {
      intercept[AssertionError](addAndCheck(blockFlow, block)).getMessage is
        s"Right(ExistInvalidTx(TxScriptExeFailed($failure)))"
    }

    def fail(
        blockFlow: BlockFlow,
        chainIndex: ChainIndex,
        script: StatefulScript,
        failure: ExeFailure
    ) = {
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage is
        s"Right(TxScriptExeFailed($failure))"
    }

    def checkContractState(
        contractId: String,
        contractAssetRef: ContractOutputRef,
        existed: Boolean
    ): Assertion = {
      val worldState  = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractKey = Hash.from(Hex.from(contractId).get).get
      worldState.contractState.exist(contractKey) isE existed
      worldState.outputState.exist(contractAssetRef) isE existed
    }
  }

  it should "not use up contract assets" in new ContractFixture {
    val input =
      """
         |TxContract Foo() {
         |  pub payable fn foo(address: Address) -> () {
         |    transferAlphFromSelf!(address, alphRemaining!(selfAddress!()))
         |  }
         |}
         |""".stripMargin

    val contractId = createContractAndCheckState(input, 2, 2, AVector.empty).key

    val main =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    let foo = Foo(#${contractId.toHexString})
         |    foo.foo(@${genesisAddress.toBase58})
         |  }
         |}
         |
         |$input
         |""".stripMargin

    val script = Compiler.compileTxScript(main).rightValue
    fail(blockFlow, chainIndex, script, EmptyContractAsset)
  }

  it should "use latest worldstate when call external functions" in new ContractFixture {
    val input0 =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
         |    return x
         |  }
         |
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |
         |TxContract Bar(mut x: U256) {
         |  pub fn bar(foo: ByteVec) -> (U256) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |""".stripMargin
    val contractOutputRef0 = createContractAndCheckState(input0, 2, 2)
    val contractKey0       = contractOutputRef0.key

    val input1 =
      s"""
         |TxContract Bar(mut x: U256) {
         |  pub fn bar(foo: ByteVec) -> (U256) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |
         |TxContract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
         |    return x
         |  }
         |
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |
         |""".stripMargin
    val contractOutputRef1 = createContractAndCheckState(input1, 3, 3)
    val contractKey1       = contractOutputRef1.key

    val main =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    let foo = Foo(#${contractKey0.toHexString})
         |    foo.foo(#${contractKey0.toHexString}, #${contractKey1.toHexString})
         |  }
         |}
         |
         |TxContract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
         |    return x
         |  }
         |
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |""".stripMargin
    val newState = AVector[Val](Val.U256(U256.unsafe(110)))
    testSimpleScript(main)

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().rightValue.length is 3

    checkState(
      blockFlow,
      chainIndex,
      contractKey0,
      newState,
      contractOutputRef0,
      numAssets = 5,
      numContracts = 3
    )
  }

  it should "issue new token" in new ContractFixture {
    val input =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contractOutputRef = createContractAndCheckState(input, 2, 2, tokenAmount = Some(10000000))
    val contractKey       = contractOutputRef.key

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().rightValue.length is 2
    worldState.getContractOutputs(ByteString.empty, Int.MaxValue).rightValue.foreach {
      case (ref, output) =>
        if (ref != ContractOutputRef.forSMT) {
          output.tokens.head is (contractKey -> U256.unsafe(10000000))
        }
    }
  }

  // scalastyle:off method.length
  it should "test operators" in new ContractFixture {
    // scalastyle:off no.equal
    def expect(out: Int) =
      s"""
         |TxScript Inverse {
         |  pub fn main() -> () {
         |    let x = 10973
         |    let mut y = 1
         |    let mut i = 0
         |    while (i <= 8) {
         |      y = y ⊗ (2 ⊖ x ⊗ y)
         |      i = i + 1
         |    }
         |    let r = x ⊗ y
         |    assert!(r == $out)
         |
         |    test()
         |  }
         |
         |  fn test() -> () {
         |    assert!((33 + 2 - 3) * 5 / 7 % 11 == 0)
         |
         |    let x = 0
         |    let y = 1
         |    assert!(x << 1 == 0)
         |    assert!(x >> 1 == 0)
         |    assert!(y << 1 == 2)
         |    assert!(y >> 1 == 0)
         |    assert!(y << 255 != 0)
         |    assert!(y << 256 == 0)
         |    assert!(x & x == 0)
         |    assert!(x & y == 0)
         |    assert!(y & y == 1)
         |    assert!(x | x == 0)
         |    assert!(x | y == 1)
         |    assert!(y | y == 1)
         |    assert!(x ^ x == 0)
         |    assert!(x ^ y == 1)
         |    assert!(y ^ y == 0)
         |
         |    assert!((x < y) == true)
         |    assert!((x <= y) == true)
         |    assert!((x < x) == false)
         |    assert!((x <= x) == true)
         |    assert!((x > y) == false)
         |    assert!((x >= y) == false)
         |    assert!((x > x) == false)
         |    assert!((x >= x) == true)
         |
         |    assert!((true && true) == true)
         |    assert!((true && false) == false)
         |    assert!((false && false) == false)
         |    assert!((true || true) == true)
         |    assert!((true || false) == true)
         |    assert!((false || false) == false)
         |
         |    assert!(!true == false)
         |    assert!(!false == true)
         |  }
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    testSimpleScript(expect(1))
    failSimpleScript(expect(2), AssertionFailed)
  }
  // scalastyle:on method.length

  // scalastyle:off no.equal
  it should "test ByteVec instructions" in new ContractFixture {
    def encode[T: Serde](t: T): String = Hex.toHexString(serialize(t))

    val i256    = UnsecureRandom.nextI256()
    val u256    = UnsecureRandom.nextU256()
    val address = Address.from(LockupScript.p2c(Hash.random))
    val bytes0  = Hex.toHexString(Hash.random.bytes)
    val bytes1  = Hex.toHexString(Hash.random.bytes)

    val main: String =
      s"""
         |TxScript ByteVecTest {
         |  pub fn main() -> () {
         |    assert!(byteVec!(true) == #${encode(true)})
         |    assert!(byteVec!(false) == #${encode(false)})
         |    assert!(byteVec!(${i256}i) == #${encode(i256)})
         |    assert!(byteVec!(${u256}) == #${encode(u256)})
         |    assert!(byteVec!(@${address.toBase58}) == #${encode(address.lockupScript)})
         |    assert!((#${bytes0} ++ #${bytes1}) == #${bytes0 ++ bytes1})
         |    assert!(size!(byteVec!(true)) == 1)
         |    assert!(size!(byteVec!(false)) == 1)
         |    assert!(size!(byteVec!(@${address.toBase58})) == 33)
         |    assert!(size!(#${bytes0} ++ #${bytes1}) == 64)
         |  }
         |}
         |""".stripMargin

    testSimpleScript(main)
  }

  // scalastyle:off no.equal
  it should "test contract instructions" in new ContractFixture {
    def createContract(input: String): (String, String, String, String) = {
      val contractId    = createContract(input, initialState = AVector.empty).key
      val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      val contractState = worldState.getContractState(contractId).rightValue
      val address       = Address.Contract(LockupScript.p2c(contractId)).toBase58
      (
        contractId.toHexString,
        address,
        contractState.initialStateHash.toHexString,
        contractState.codeHash.toHexString
      )
    }

    val foo =
      s"""
         |TxContract Foo() {
         |  pub fn foo(fooId: ByteVec, fooHash: ByteVec, fooCodeHash: ByteVec, barId: ByteVec, barHash: ByteVec, barCodeHash: ByteVec, barAddress: Address) -> () {
         |    assert!(selfContractId!() == fooId)
         |    assert!(contractInitialStateHash!(fooId) == fooHash)
         |    assert!(contractInitialStateHash!(barId) == barHash)
         |    assert!(contractCodeHash!(fooId) == fooCodeHash)
         |    assert!(contractCodeHash!(barId) == barCodeHash)
         |    assert!(callerContractId!() == barId)
         |    assert!(callerAddress!() == barAddress)
         |    assert!(callerInitialStateHash!() == barHash)
         |    assert!(callerCodeHash!() == barCodeHash)
         |    assert!(isCalledFromTxScript!() == false)
         |    assert!(isAssetAddress!(barAddress) == false)
         |    assert!(isContractAddress!(barAddress) == true)
         |  }
         |}
         |""".stripMargin
    val (fooId, _, fooHash, fooCodeHash) = createContract(foo)

    val bar =
      s"""
         |TxContract Bar() {
         |  pub payable fn bar(fooId: ByteVec, fooHash: ByteVec, fooCodeHash: ByteVec, barId: ByteVec, barHash: ByteVec, barCodeHash: ByteVec, barAddress: Address) -> () {
         |    assert!(selfContractId!() == barId)
         |    assert!(selfAddress!() == barAddress)
         |    assert!(contractInitialStateHash!(fooId) == fooHash)
         |    assert!(contractInitialStateHash!(barId) == barHash)
         |    Foo(#$fooId).foo(fooId, fooHash, fooCodeHash, barId, barHash, barCodeHash, barAddress)
         |    assert!(isCalledFromTxScript!() == true)
         |    assert!(isPaying!(@$genesisAddress) == false)
         |    assert!(isAssetAddress!(@$genesisAddress) == true)
         |    assert!(isContractAddress!(@$genesisAddress) == false)
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val (barId, barAddress, barHash, barCodeHash) = createContract(bar)

    def main(state: String) =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    Bar(#$barId).bar(#$fooId, #$fooHash, #$fooCodeHash, #$barId, #$barHash, #$barCodeHash, @$barAddress)
         |    approveAlph!(@$genesisAddress, ${ALPH.alph(1).v})
         |    copyCreateContract!(#$fooId, #$state)
         |    assert!(isPaying!(@$genesisAddress) == true)
         |  }
         |}
         |
         |$bar
         |""".stripMargin

    {
      val script = Compiler.compileTxScript(main("00")).rightValue
      val block  = payableCall(blockFlow, chainIndex, script)
      addAndCheck(blockFlow, block)
    }

    {
      info("Try to create a new contract with invalid number of fields")
      val script = Compiler.compileTxScript(main("010001")).rightValue
      fail(blockFlow, chainIndex, script, InvalidFieldLength)
    }
  }

  trait DestroyFixture extends ContractFixture {
    def prepareContract(
        contract: String,
        initialState: AVector[Val] = AVector.empty
    ): (String, ContractOutputRef) = {
      val contractId       = createContract(contract, initialState).key
      val worldState       = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractAssetRef = worldState.getContractState(contractId).rightValue.contractOutputRef
      contractId.toHexString -> contractAssetRef
    }
  }

  it should "destroy contract" in new DestroyFixture {
    val foo =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub payable fn destroy(targetAddress: Address) -> () {
         |    x = x + 1
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo, AVector(Val.U256(0)))
    checkContractState(fooId, fooAssetRef, true)

    def main(targetAddress: String) =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    Foo(#$fooId).destroy(@$targetAddress)
         |  }
         |}
         |
         |$foo
         |""".stripMargin

    {
      info("Destroy a contract with contract address")
      val address = Address.Contract(LockupScript.P2C(Hash.generate))
      val script  = Compiler.compileTxScript(main(address.toBase58)).rightValue
      fail(blockFlow, chainIndex, script, InvalidAddressTypeInContractDestroy)
      checkContractState(fooId, fooAssetRef, true)
    }

    {
      info("Destroy a contract twice, this should fail")
      val main =
        s"""
           |TxScript Main {
           |  pub payable fn main() -> () {
           |    Foo(#$fooId).destroy(@${genesisAddress.toBase58})
           |    Foo(#$fooId).destroy(@${genesisAddress.toBase58})
           |  }
           |}
           |
           |$foo
           |""".stripMargin
      val script = Compiler.compileTxScript(main).rightValue
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
        .startsWith("Right(TxScriptExeFailed(NonExistContract") is true
      checkContractState(fooId, fooAssetRef, true) // None of the two destruction will take place
    }

    {
      info("Destroy a contract properly")
      callTxScript(main(genesisAddress.toBase58))
      checkContractState(fooId, fooAssetRef, false)
    }
  }

  it should "call contract destroy function from another contract" in new DestroyFixture {
    val foo =
      s"""
         |TxContract Foo() {
         |  pub payable fn destroy(targetAddress: Address) -> () {
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo)
    checkContractState(fooId, fooAssetRef, true)

    val bar =
      s"""
         |TxContract Bar() {
         |  pub payable fn bar(targetAddress: Address) -> () {
         |    Foo(#$fooId).destroy(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector.empty).key.toHexString

    val main =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    Bar(#$barId).bar(@${genesisAddress.toBase58})
         |  }
         |}
         |
         |$bar
         |""".stripMargin

    callTxScript(main)
    checkContractState(fooId, fooAssetRef, false)
  }

  it should "not call contract destroy function from the same contract" in new DestroyFixture {
    val foo =
      s"""
         |TxContract Foo() {
         |  pub payable fn foo(targetAddress: Address) -> () {
         |    approveAlph!(selfAddress!(), alphRemaining!(selfAddress!()))
         |    destroy(targetAddress)
         |  }
         |
         |  pub payable fn destroy(targetAddress: Address) -> () {
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo)
    checkContractState(fooId, fooAssetRef, true)

    val main =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    Foo(#$fooId).foo(@${genesisAddress.toBase58})
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val script = Compiler.compileTxScript(main).rightValue
    intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage.startsWith(
      "Left(org.alephium.io.IOError$KeyNotFound: org.alephium.util.AppException: Key ContractOutputRef("
    ) is true
  }

  it should "fetch block env" in new ContractFixture {
    def main(latestHeader: BlockHeader) =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    assert!(networkId!() == #02)
         |    assert!(blockTimeStamp!() >= ${latestHeader.timestamp.millis})
         |    assert!(blockTarget!() == ${latestHeader.target.value})
         |  }
         |}
         |""".stripMargin

    def test() = {
      val latestTip    = blockFlow.getHeaderChain(chainIndex).getBestTipUnsafe()
      val latestHeader = blockFlow.getBlockHeaderUnsafe(latestTip)
      testSimpleScript(main(latestHeader))
    }

    // we test with three new blocks
    test()
    test()
    test()
  }

  it should "fetch tx env" in new ContractFixture {
    val zeroId = Hash.zero
    def main(index: Int) =
      s"""
         |TxScript TxEnv {
         |  pub fn main() -> () {
         |    assert!(txId!() != #${zeroId.toHexString})
         |    assert!(txCaller!($index) == @${genesisAddress.toBase58})
         |    assert!(txCallerSize!() == 1)
         |  }
         |}
         |""".stripMargin
    testSimpleScript(main(0))
    failSimpleScript(main(1), InvalidTxInputIndex)
  }

  // scalastyle:off regex
  it should "test hash built-ins" in new ContractFixture {
    val input = Hex.toHexString(ByteString.fromString("Hello World1"))
    val main =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    assert!(blake2b!(#$input) == #8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b37a)
         |    assert!(blake2b!(#$input) != #8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b370)
         |    assert!(keccak256!(#$input) == #2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E3C)
         |    assert!(keccak256!(#$input) != #2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E30)
         |    assert!(sha256!(#$input) == #6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A9A)
         |    assert!(sha256!(#$input) != #6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A90)
         |    assert!(sha3!(#$input) == #f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed743)
         |    assert!(sha3!(#$input) != #f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed740)
         |  }
         |}
         |""".stripMargin
    testSimpleScript(main)
  }

  // scalastyle:off no.equal
  it should "test signature built-ins" in new ContractFixture {
    val zero                     = Hash.zero.toHexString
    val (p256Pri, p256Pub)       = SecP256K1.generatePriPub()
    val p256Sig                  = SecP256K1.sign(Hash.zero.bytes, p256Pri).toHexString
    val (ed25519Pri, ed25519Pub) = ED25519.generatePriPub()
    val ed25519Sig               = ED25519.sign(Hash.zero.bytes, ed25519Pri).toHexString
    def main(p256Sig: String, ed25519Sig: String) =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    verifySecP256K1!(#$zero, #${p256Pub.toHexString}, #$p256Sig)
         |    verifyED25519!(#$zero, #${ed25519Pub.toHexString}, #$ed25519Sig)
         |  }
         |}
         |""".stripMargin
    testSimpleScript(main(p256Sig, ed25519Sig))
    failSimpleScript(main(SecP256K1Signature.zero.toHexString, ed25519Sig), InvalidSignature)
    failSimpleScript(main(p256Sig, ED25519Signature.zero.toHexString), InvalidSignature)
  }

  it should "test locktime built-ins" in new ContractFixture {
    // avoid genesis blocks due to genesis timestamp
    val block = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)

    def main(absoluteTimeLock: TimeStamp, relativeTimeLock: Duration, txIndex: Int) =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    verifyAbsoluteLocktime!(${absoluteTimeLock.millis})
         |    verifyRelativeLocktime!(${txIndex}, ${relativeTimeLock.millis})
         |  }
         |}
         |""".stripMargin
    testSimpleScript(main(block.timestamp, Duration.unsafe(1), 0))
    failSimpleScript(main(block.timestamp, Duration.unsafe(1), 1), InvalidTxInputIndex)
    failSimpleScript(
      main(TimeStamp.now() + Duration.ofMinutesUnsafe(1), Duration.unsafe(1), 0),
      AbsoluteLockTimeVerificationFailed
    )
    failSimpleScript(
      main(block.timestamp, Duration.ofMinutesUnsafe(1), 0),
      RelativeLockTimeVerificationFailed
    )
  }

  it should "create and use NFT contract" in new ContractFixture {
    val nftContract =
      s"""
         |// credits to @chloekek
         |TxContract Nft(author: Address, price: U256)
         |{
         |    pub payable fn buy(buyer: Address) -> ()
         |    {
         |        transferAlph!(buyer, author, price)
         |        transferTokenFromSelf!(buyer, selfTokenId!(), 1)
         |        destroySelf!(author)
         |    }
         |}
         |""".stripMargin
    val tokenId =
      createContractAndCheckState(
        nftContract,
        2,
        2,
        initialState =
          AVector[Val](Val.Address(genesisAddress.lockupScript), Val.U256(U256.unsafe(1000000))),
        tokenAmount = Some(1024)
      ).key

    callTxScript(
      s"""
         |TxScript Main
         |{
         |    pub payable fn main() -> ()
         |    {
         |        approveAlph!(@${genesisAddress.toBase58}, 1000000)
         |        Nft(#${tokenId.toHexString}).buy(@${genesisAddress.toBase58})
         |    }
         |}
         |
         |$nftContract
         |""".stripMargin
    )
  }

  it should "create and use Uniswap-like contract" in new ContractFixture {
    val tokenContract =
      s"""
         |TxContract Token(mut x: U256) {
         |  pub payable fn withdraw(address: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
         |  }
         |}
         |""".stripMargin
    val tokenContractKey =
      createContractAndCheckState(tokenContract, 2, 2, tokenAmount = Some(1024)).key
    val tokenId = tokenContractKey

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    let token = Token(#${tokenContractKey.toHexString})
                    |    token.withdraw(@${genesisAddress.toBase58}, 1024)
                    |  }
                    |}
                    |
                    |$tokenContract
                    |""".stripMargin)

    val swapContract =
      s"""
         |// Simple swap contract purely for testing
         |
         |TxContract Swap(tokenId: ByteVec, mut alphReserve: U256, mut tokenReserve: U256) {
         |
         |  pub payable fn addLiquidity(lp: Address, alphAmount: U256, tokenAmount: U256) -> () {
         |    transferAlphToSelf!(lp, alphAmount)
         |    transferTokenToSelf!(lp, tokenId, tokenAmount)
         |    alphReserve = alphAmount
         |    tokenReserve = tokenAmount
         |  }
         |
         |  pub payable fn swapToken(buyer: Address, alphAmount: U256) -> () {
         |    let tokenAmount = tokenReserve - alphReserve * tokenReserve / (alphReserve + alphAmount)
         |    transferAlphToSelf!(buyer, alphAmount)
         |    transferTokenFromSelf!(buyer, tokenId, tokenAmount)
         |    alphReserve = alphReserve + alphAmount
         |    tokenReserve = tokenReserve - tokenAmount
         |  }
         |
         |  pub payable fn swapAlph(buyer: Address, tokenAmount: U256) -> () {
         |    let alphAmount = alphReserve - alphReserve * tokenReserve / (tokenReserve + tokenAmount)
         |    transferTokenToSelf!(buyer, tokenId, tokenAmount)
         |    transferAlphFromSelf!(buyer, alphAmount)
         |    alphReserve = alphReserve - alphAmount
         |    tokenReserve = tokenReserve + tokenAmount
         |  }
         |}
         |""".stripMargin
    val swapContractKey = createContract(
      swapContract,
      AVector[Val](Val.ByteVec.from(tokenId), Val.U256(U256.Zero), Val.U256(U256.Zero)),
      tokenAmount = Some(1024)
    ).key

    def checkSwapBalance(alphReserve: U256, tokenReserve: U256) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val output     = worldState.getContractAsset(swapContractKey).rightValue
      output.amount is alphReserve
      output.tokens.toSeq.toMap.getOrElse(tokenId, U256.Zero) is tokenReserve
    }

    checkSwapBalance(dustUtxoAmount, 0)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    approveAlph!(@${genesisAddress.toBase58}, 10)
                    |    approveToken!(@${genesisAddress.toBase58}, #${tokenId.toHexString}, 100)
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.addLiquidity(@${genesisAddress.toBase58}, 10, 100)
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(dustUtxoAmount + 10, 100)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    approveAlph!(@${genesisAddress.toBase58}, 10)
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.swapToken(@${genesisAddress.toBase58}, 10)
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(dustUtxoAmount + 20, 50)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    approveToken!(@${genesisAddress.toBase58}, #${tokenId.toHexString}, 50)
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.swapAlph(@${genesisAddress.toBase58}, 50)
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(dustUtxoAmount + 10, 100)
  }

  it should "execute tx in random order" in new ContractFixture {
    val testContract =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn foo(y: U256) -> () {
         |    x = x * 10 + y
         |  }
         |}
         |""".stripMargin
    val contractKey = createContractAndCheckState(testContract, 2, 2).key

    val block = callTxScriptMulti(index => s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    let foo = Foo(#${contractKey.toHexString})
         |    foo.foo($index)
         |  }
         |}
         |
         |$testContract
         |""".stripMargin)

    val expected      = block.getNonCoinbaseExecutionOrder.fold(0L)(_ * 10 + _)
    val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(contractKey).fold(throw _, identity)
    contractState.fields is AVector[Val](Val.U256(U256.unsafe(expected)))
  }

  it should "be able to call a contract multiple times in a block" in new ContractFixture {
    val testContract =
      s"""
        |TxContract Foo(mut x: U256) {
        |  pub payable fn foo(address: Address) -> () {
        |    x = x + 1
        |    transferAlphFromSelf!(address, ${ALPH.cent(1).v})
        |  }
        |}
        |""".stripMargin
    val contractId =
      createContractAndCheckState(
        testContract,
        2,
        2,
        initialAlphAmount = ALPH.alph(1)
      ).key

    def checkContract(alphReserve: U256, x: Int) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      val state      = worldState.getContractState(contractId).rightValue
      state.fields is AVector[Val](Val.U256(x))
      val output = worldState.getContractAsset(contractId).rightValue
      output.amount is alphReserve
    }

    checkContract(ALPH.alph(1), 0)

    val block0 = transfer(blockFlow, chainIndex, amount = ALPH.alph(10), numReceivers = 10)
    addAndCheck(blockFlow, block0)
    val newAddresses = block0.nonCoinbase.head.unsigned.fixedOutputs.init.map(_.lockupScript)

    def main(address: LockupScript.Asset) =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    let foo = Foo(#${contractId.toHexString})
         |    foo.foo(@${Address.Asset(address).toBase58})
         |  }
         |}
         |
         |$testContract
         |""".stripMargin

    val validator = TxValidation.build
    val simpleTx  = transfer(blockFlow, chainIndex).nonCoinbase.head.toTemplate
    blockFlow.getMemPool(chainIndex).addNewTx(chainIndex, simpleTx, TimeStamp.now())
    newAddresses.foreachWithIndex { case (address, index) =>
      val gas    = if (index % 2 == 0) 20000 else 200000
      val script = Compiler.compileTxScript(main(address)).rightValue
      val tx = payableCallTxTemplate(
        blockFlow,
        chainIndex,
        address,
        script,
        initialGas = gas,
        validation = false
      )

      if (index % 2 == 0) {
        assume(tx.chainIndex == chainIndex)
        validator.validateMempoolTxTemplate(tx, blockFlow).leftValue isE TxScriptExeFailed(OutOfGas)
      } else {
        validator.validateMempoolTxTemplate(tx, blockFlow) isE ()
      }
      blockFlow
        .getMemPool(chainIndex)
        .addNewTx(chainIndex, tx, TimeStamp.now()) is AddedToSharedPool
    }

    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex))
    blockTemplate.transactions.length is 12
    blockTemplate.transactions.filter(_.scriptExecutionOk == false).length is 5
    val block = mine(blockFlow, blockTemplate)
    addAndCheck0(blockFlow, block)
    checkContract(ALPH.cent(95), 5)
  }
}
// scalastyle:on file.size.limit no.equal regex
