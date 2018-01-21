package org.constellation.wallet


import java.security.KeyPair
import org.scalatest.FlatSpec

import KeyUtils._

class TestWalletFuncs  extends FlatSpec {

  val kp: KeyPair = makeKeyPair()

  "KeyGen" should "make proper keys" in {
    val privK = kp.getPrivate.toString
    val pubK = kp.getPublic.toString
    Seq(privK, pubK).foreach { pk =>
      assert(pk.length > 50)
      assert(pk.contains(":"))
      assert(pk.contains("X:"))
      assert(pk.split("\n").length > 2)
    }
  }

  "Signature" should "sign and verify output" in {

    val text = "Yo this some text"
    val inputBytes = text.getBytes()

    val signedOutput = signData(inputBytes)(kp.getPrivate)
    val isLegit = verifySignature(inputBytes, signedOutput)(kp.getPublic)

    assert(isLegit)

  }

  "Key Size" should "verify byte array lengths for encoded keys" in {

    def fill(thunk: => Array[Byte]) =
      Seq.fill(50){thunk}.map{_.length}.distinct

    assert(fill(makeKeyPair().getPrivate.getEncoded) == List(144))
    assert(fill(makeKeyPair().getPublic.getEncoded) == List(88))

  }

  "Address maker" should "create an address" in {
    val addr = publicKeyToAddress(kp.getPublic)
    assert(addr.length > 10)
    assert(addr.toCharArray.distinct.length > 5)
  }

  val sampleTransactionInput = TransactionInputData(kp.getPublic, publicKeyToAddress(kp.getPublic), 1L)

  "Transaction Input Data" should "render to json" in {
    val r = sampleTransactionInput.encode.rendered
    assert(r.contains('['))
    assert(r.length > 50)
    assert(r.toCharArray.distinct.length > 5)
    assert(r.contains('"'))
  }

  "Transaction Encoding" should "encode and decode transactions" in {
    val enc = sampleTransactionInput.encode
    assert(enc.decode == sampleTransactionInput)
    val rendParse = txFromString(enc.rendered)
    assert(rendParse == enc)
    assert(rendParse.decode == sampleTransactionInput)
  }

  "Key Encoding" should "verify keys can be encoded and decoded with X509 spec" in {
    val pub1 = kp.getPublic
    val priv1 = kp.getPrivate

    val encodedBytesPub = pub1.getEncoded
    val pub2 = bytesToPublicKey(encodedBytesPub)
    assert(pub1 == pub2)
    assert(pub1.getEncoded.sameElements(pub2.getEncoded))

    val encodedBytesPriv = priv1.getEncoded
    val priv2 = bytesToPrivateKey(encodedBytesPriv)
    assert(priv1 == priv2)
    assert(priv1.getEncoded.sameElements(priv2.getEncoded))
  }

}
