package org.sunbird.job.certpublic.functions

import org.sunbird.job.certpublic.task.CertificateGeneratorConfig
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class EncryptService(config: CertificateGeneratorConfig) {
  private val ALGORITHM = "AES"
  private val ITERATIONS = 3
  private val keyValue = "ThisAsISerceKtey".getBytes(StandardCharsets.UTF_8)

  private val cipher: Cipher = {
    val c = Cipher.getInstance(ALGORITHM)
    c.init(Cipher.ENCRYPT_MODE, generateKey())
    c
  }

  private def generateKey(): SecretKeySpec = {
    new SecretKeySpec(keyValue, ALGORITHM)
  }

  def main(args: Array[String]): Unit = {
    val encryptService = new EncryptService(config)
    val emailData = "7MGqvsCCCif@ymail.com"
    val encryptedEmail = encryptService.encryptData(emailData)
    println(s"Encrypted Email Data: $encryptedEmail")
  }

  @throws[Exception]
  def encryptData(value: String): String = {
    var valueToEnc: String = null
    val encryptionKey: String = config.encryptionKey
    var eValue = value
    for (_ <- 0 until ITERATIONS) {
      valueToEnc = encryptionKey + eValue
      val encValue =
        cipher.doFinal(valueToEnc.getBytes(StandardCharsets.UTF_8))
      eValue = Base64.getEncoder.encodeToString(encValue)
    }
    eValue
  }
}
