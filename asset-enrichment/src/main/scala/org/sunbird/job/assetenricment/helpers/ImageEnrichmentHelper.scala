package org.sunbird.job.assetenricment.helpers

import org.apache.commons.lang.StringUtils
import org.im4java.core.Info
import org.slf4j.LoggerFactory
import org.sunbird.job.assetenricment.models.Asset
import org.sunbird.job.assetenricment.task.AssetEnrichmentConfig
import org.sunbird.job.assetenricment.util.{AssetFileUtils, ImageResizerUtil}
import org.sunbird.job.domain.`object`.DefinitionCache
import org.sunbird.job.util.{CloudStorageUtil, FileUtils, Neo4JUtil, ScalaJsonUtil, Slug}

import java.io.File
import java.net.URL
import scala.collection.mutable
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter

trait ImageEnrichmentHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[ImageEnrichmentHelper])
  private val CONTENT_FOLDER = "content"
  private val ARTIFACT_FOLDER = "artifact"

  def enrichImage(asset: Asset)(implicit config: AssetEnrichmentConfig, definitionCache: DefinitionCache, cloudStorageUtil: CloudStorageUtil, neo4JUtil: Neo4JUtil): Unit = {
    val downloadUrl = asset.get("artifactUrl", "").asInstanceOf[String]
    try {
      val variantsMap = optimizeImage(asset.identifier, downloadUrl)(config, definitionCache, cloudStorageUtil)
      saveImageVariants(variantsMap, asset)(neo4JUtil)
    } catch {
      case e: Exception =>
        logger.error(s"Something Went Wrong While Performing Asset Enrichment operation.Content Id: ${asset.identifier}", e)
        asset.put("processingError", e.getMessage)
        asset.put("status", "Failed")
        neo4JUtil.updateNode(asset.identifier, asset.getMetadata)
        throw e
    }
  }

  def getDownloadableURL(filePath: String)(implicit storageUtil: CloudStorageUtil, config: AssetEnrichmentConfig): String = {
    val downloadableUrl: String = if (filePath.contains(config.getString("cloud_storage_endpoint", "http"))) {
      val uri:String = StringUtils.substringAfter(new URL(filePath).getPath, "/")
      val container = StringUtils.substringBefore(uri ,"/")
      val relativePath = StringUtils.substringAfter(uri, "/")
      logger.info("Got filePath with relative path: " + relativePath)
      storageUtil.getSignedUrl(container, relativePath, 30)
    } else {
      filePath
    }
    downloadableUrl
  }

  def optimizeImage(contentId: String, originalURL: String)(implicit config: AssetEnrichmentConfig, definitionCache: DefinitionCache, cloudStorageUtil: CloudStorageUtil): Map[String, String] = {
    val variantsMap = mutable.Map[String, String]()
    val variants = getVariant()(definitionCache, config)

    val downloadableURL = getDownloadableURL(originalURL)
    val originalFile = FileUtils.copyURLToFile(contentId, downloadableURL, originalURL.substring(originalURL.lastIndexOf("/") + 1, originalURL.length))
    try {
      originalFile match {
        case Some(file: File) => variants.foreach(variant => {
          val resolution = variant._1
          val variantValueMap = variant._2.asInstanceOf[Map[String, AnyRef]]
          val dimension = variantValueMap.getOrElse("dimensions", List[Int]()).asInstanceOf[List[Int]]
          val dpi = variantValueMap.getOrElse("dpi", 0).asInstanceOf[Int]
          if (dimension == null || dimension.size != 2) throw new Exception("Content Optimizer Error. Image Resolution/variants is not configured for content optimization.")
          logger.info("width : ",dimension(0))
          logger.info("hieght : ",dimension(1))
          if (isImageOptimizable(file, dimension(0), dimension(1))) {
            logger.info("inside else if condtion")
            val targetResolution = getOptimalDPI(file, dpi)
            val optimisedFile = optimizeImage(file, targetResolution, dimension(0), dimension(1), resolution)
            if (null != optimisedFile && optimisedFile.exists) {
              val optimisedURLArray = upload(optimisedFile, contentId)(cloudStorageUtil)
              variantsMap.put(resolution, optimisedURLArray(1))
            }
          } else {
            logger.info("inside else condtion")
            variantsMap.put(resolution, originalURL)
          }
        })
        case _ => logger.error("ERR_INVALID_FILE_URL", s"Please Provide Valid File Url for identifier: $contentId!")
          throw new Exception(s"Please Provide Valid File Url for identifier : $contentId and URL : $originalURL.")
      }
    } finally {
      FileUtils.deleteDirectory(new File(s"/tmp/$contentId"))
    }
    if (variantsMap.getOrElse("medium", "").isEmpty && originalURL.nonEmpty) variantsMap.put("medium", originalURL)
    variantsMap.toMap
  }

  private def getVariant()(implicit definitionCache: DefinitionCache, config: AssetEnrichmentConfig): Map[String, AnyRef] = {
    val version = config.schemaSupportVersionMap.getOrElse("asset", "1.0")
    val definition = definitionCache.getDefinition("Asset", version, config.definitionBasePath)
    val variants = definition.config.getOrElse("variants", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
    variants
  }

  private def optimizeImage(file: File, dpi: Double, width: Int, height: Int, resolution: String): File = {
    val fileType = AssetFileUtils.getFileType(file)
    val proc = new ImageResizerUtil
    if (proc.isApplicable(fileType)) {
      val image = ImmutableImage.loader().fromFile(file)
      val resizedImage = image.scaleTo(width, height)
      val outputFile = new File(file.getParent, s"optimized_${file.getName}")
      resizedImage.output(JpegWriter.Default, outputFile)
      outputFile
    } else null
  }

  def isImageOptimizable(file: File, dimensionX: Int, dimensionY: Int): Boolean = {
    try {
      val image = ImmutableImage.loader().fromFile(file)
      val width = image.width
      val height = image.height
      (dimensionX < width && dimensionY < height)
    } catch {
      case e: Exception =>
        logger.error("Error while getting Image Info using Scrimage", e)
        throw new Exception("Failed to get image dimensions using Scrimage", e)
    }
  }

  def getOptimalDPI(file: File, dpi: Int): Double = {
    try {
      val image = ImmutableImage.loader().fromFile(file)
      val width = image.width
      val height = image.height
      val resolution = Math.min(width, height).toDouble
      Math.min(resolution, dpi.toDouble)
    } catch {
      case e: Exception =>
        logger.error("Error while getting DPI from Image using Scrimage", e)
        throw new Exception("Failed to get DPI using Scrimage", e)
    }
  }

  def saveImageVariants(variantsMap: Map[String, String], asset: Asset)(implicit neo4JUtil: Neo4JUtil): Unit = {
    if (variantsMap.nonEmpty) asset.put("variants", ScalaJsonUtil.serialize(variantsMap))
    asset.put("status", "Live")
    logger.info(s"Processed Image for identifier: ${asset.identifier}. Updating metadata.")
    neo4JUtil.updateNode(asset.identifier, asset.getMetadata)
  }

  def upload(file: File, identifier: String)(implicit cloudStorageUtil: CloudStorageUtil): Array[String] = {
    try {
      val slug = Slug.makeSlug(identifier, isTransliterate = true)
      val folder = s"$CONTENT_FOLDER/$slug/$ARTIFACT_FOLDER"
      cloudStorageUtil.uploadFile(folder, file, Some(true))
    } catch {
      case e: Exception =>
        throw new Exception(s"Error while uploading the File for identifier : $identifier.", e)
    }
  }

}
