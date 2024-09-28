package org.sunbird.job.certpublic.functions


import org.sunbird.job.certpublic.task.CertificateGeneratorConfig

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class DecryptService(config: CertificateGeneratorConfig) {
  private val ALGORITHM = "AES"
  private val ITERATIONS = 3
  private val keyValue = "ThisAsISerceKey".getBytes

  val decryptService = new DecryptService(config)

  def main(args: Array[String]): Unit = {
    val decryptedData = decryptService.decryptData("2E+UW4MIzDTr4ygsu7KRErx3XFNTg7lyRmiTQsf8AN/OHMw/Yn8xUq8P6kDk08KICpPF2KkVj+wX\\npqiq0F6bOzfj8ZMA9H5uzNqTSr+zjWfJCHYyXXaTaJyHcEIsKRJsT6a+wzaAmCWueMEdPmZuRg==")
    println(s"Decrypted Data: $decryptedData")
  }

  private val cipher: Cipher = {
    val c = Cipher.getInstance(ALGORITHM)
    c.init(Cipher.DECRYPT_MODE, generateKey())
    c
  }

  private def generateKey(): SecretKeySpec = {
    new SecretKeySpec(keyValue, ALGORITHM)
  }

  @throws[Exception]
  def decryptData(value: String): String = {
    var valueToDecrypt = value.trim
    val sunbirdEncryption = config.encryptionKey
    var dValue: String = null
    for (_ <- 0 until ITERATIONS) {
      val decodedValue = Base64.getDecoder.decode(valueToDecrypt)
      val decValue = cipher.doFinal(decodedValue)
      dValue = new String(decValue, StandardCharsets.UTF_8).substring(sunbirdEncryption.length)
      valueToDecrypt = dValue
    }
    dValue
  }
}
