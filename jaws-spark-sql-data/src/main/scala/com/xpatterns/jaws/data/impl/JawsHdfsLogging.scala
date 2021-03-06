package com.xpatterns.jaws.data.impl

import java.util

import org.apache.hadoop.conf.Configuration
import org.apache.log4j.Logger
import com.xpatterns.jaws.data.contracts.TJawsLogging
import com.xpatterns.jaws.data.DTO.Logs
import java.util.Comparator
import com.xpatterns.jaws.data.DTO.Log
import com.xpatterns.jaws.data.DTO.Queries
import com.xpatterns.jaws.data.DTO.Query
import com.xpatterns.jaws.data.DTO.QueryMetaInfo
import spray.json._
import com.xpatterns.jaws.data.utils.QueryState
import com.xpatterns.jaws.data.utils.Utils

import scala.collection.mutable.ArrayBuffer

class JawsHdfsLogging(configuration: Configuration) extends TJawsLogging {

  val QUERYID_SEPARATOR = "-----"

  val logger = Logger.getLogger("JawsHdfsLogging")

  val forcedMode = configuration.getBoolean(Utils.FORCED_MODE, false)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.LOGGING_FOLDER), forcedMode)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.STATUS_FOLDER), forcedMode)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.DETAILS_FOLDER), forcedMode)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.METAINFO_FOLDER), forcedMode)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.QUERY_NAME_FOLDER), forcedMode)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.QUERY_PUBLISHED_FOLDER), forcedMode)
  Utils.createFolderIfDoesntExist(configuration, configuration.get(Utils.QUERY_UNPUBLISHED_FOLDER), forcedMode)

  override def setState(uuid: String, queryState: QueryState.QueryState) {
    logger.debug("Writing query state " + queryState.toString + " to query " + uuid)
    Utils.rewriteFile(queryState.toString, configuration, getQueryStateFilePath(uuid))

  }

  override def setScriptDetails(queryId: String, scriptDetails: String) {

    logger.debug("Writing script details " + scriptDetails + " to query " + queryId)
    Utils.rewriteFile(scriptDetails, configuration, getQueryDetailsFilePath(queryId))

  }

  override def addLog(queryId: String, jobId: String, time: Long, log: String) {

    logger.debug("Writing log " + log + " to query " + queryId + " at time " + time)

    logger.debug("Writing log " + log + " to query " + queryId + " at time " + time)
    val folderName = getQueryLogsFolderPath(queryId)
    val fileName = folderName + "/" + time.toString
    val logMessage = jobId + QUERYID_SEPARATOR + log
    Utils.createFolderIfDoesntExist(configuration, folderName, forcedMode = false)
    Utils.rewriteFile(logMessage, configuration, fileName)

  }

  override def getState(queryId: String): QueryState.QueryState = {

    logger.debug("Reading query state for query: " + queryId)
    val filename = getQueryStateFilePath(queryId)

    if (Utils.checkFileExistence(filename, configuration)) {
      val state = Utils.readFile(configuration, filename)
      return QueryState.withName(state)
    }
    QueryState.NOT_FOUND
  }

  override def getScriptDetails(queryId: String): String = {
    logger.info("Reading script details for query: " + queryId)
    val filename = getQueryDetailsFilePath(queryId)
    if (Utils.checkFileExistence(filename, configuration)) Utils.readFile(configuration, filename) else ""
  }

  override def getLogs(queryId: String, time: Long, limit: Int): Logs = {

    logger.debug("Reading logs for query: " + queryId + " from date: " + time)

    val state = getState(queryId).toString
    val folderName = getQueryLogsFolderPath(queryId)
    var logs = Array[Log]()
    if (Utils.checkFileExistence(folderName, configuration)) {
      var files = Utils.listFiles(configuration, folderName, new Comparator[String]() {
        override def compare(o1: String, o2: String) = o1.compareTo(o2)
      })

      if (files.contains(time.toString)) {
        files = files.tailSet(time.toString)
      }

      val filesToBeRead = getSubset(limit, files)

      filesToBeRead.foreach(file => {
        val logedInfo = Utils.readFile(configuration, folderName + "/" + file).split(QUERYID_SEPARATOR)
        if (logedInfo.length == 2) {
          logs = logs ++ Array(new Log(logedInfo(1), logedInfo(0), file.toLong))

        }
      })
    }

    new Logs(logs, state)
  }

  def getSubset(limit: Int, files: util.SortedSet[String]): List[String] = {
    var filesToBeRead = List[String]()
    var limitMutable = limit

    val iterator = files.iterator()

    while (iterator.hasNext && limitMutable > 0) {
      val file = iterator.next()
      filesToBeRead = filesToBeRead ++ List(file)
      limitMutable = limitMutable - 1
    }

    filesToBeRead
  }

  override def getQueries(queryId: String, limit: Int): Queries = {
    val queryIdValue = Option(queryId).getOrElse("")
    logger.info("Reading states for queries starting with the query: " + queryIdValue)
    var stateList = Array[Query]()

    val folderName = configuration.get(Utils.STATUS_FOLDER)
    var files = Utils.listFiles(configuration, folderName, new Comparator[String]() {
      override def compare(o1: String, o2: String): Int = o2.compareTo(o1)
    })

    if (files.contains(queryIdValue)) {
      files = files.tailSet(queryIdValue)
      files.remove(queryIdValue)
    }

    val filesToBeRead = getSubset(limit, files)

    filesToBeRead.foreach(file => {
      val currentUuid = Utils.getNameFromPath(file)
      stateList = stateList ++ Array(new Query(Utils.readFile(configuration, folderName + "/" + file), currentUuid,
        getScriptDetails(currentUuid), getMetaInfo(queryId)))
    })

    new Queries(stateList)
  }

  override def setMetaInfo(queryId: String, metainfo: QueryMetaInfo) {
    logger.debug("Writing script meta info " + metainfo + " to query " + queryId)
    val buffer = metainfo.toJson.toString()
    Utils.rewriteFile(buffer, configuration, getQueryMetaInfoFilePath(queryId))
  }

  override def getMetaInfo(queryId: String): QueryMetaInfo = {
    logger.debug("Reading meta info for for query: " + queryId)
    val filePath = getQueryMetaInfoFilePath(queryId)
    if (Utils.checkFileExistence(filePath, configuration)) {
      val value = Utils.readFile(configuration, getQueryMetaInfoFilePath(queryId))
      val json = value.parseJson
      json.convertTo[QueryMetaInfo]
    } else {
      new QueryMetaInfo()
    }
  }

  override def getQueriesByName(name:String):Queries = {
    Utils.TryWithRetry {
      logger.debug(s"Reading queries states for queries with name $name")

      val filePath = getQueryNameFolderPath(name)
      if (Utils.checkFileExistence(filePath, configuration)) {
        val queryID =  Utils.readFile(configuration, filePath)
        getQueries(List(queryID))
      } else {
        new Queries(Array[Query]())
      }
    }
  }

  override def saveQueryName(name: String, queryId: String): Unit = {
    Utils.TryWithRetry {
      logger.debug("Saving query name " + name + " to query " + queryId)
      Utils.rewriteFile(queryId, configuration, getQueryNameFolderPath(name))
    }
  }

  override def deleteQueryName(name: String): Unit = {
    Utils.TryWithRetry {
      logger.debug("Deleting query name " + name)
      val filePath = getQueryNameFolderPath(name)
      Utils.deleteFile(configuration, filePath)
    }
  }

  override def getPublishedQueries():Array[String] = {
    val folderName = configuration.get(Utils.QUERY_PUBLISHED_FOLDER)
    val files = Utils.listFiles(configuration, folderName, new Comparator[String]() {
      override def compare(o1: String, o2: String): Int = o2.compareTo(o1)
    })

    files.toArray(new Array[String](files.size()))
  }

  def setQueryPublishedStatus(name: String, metaInfo: QueryMetaInfo, published: Boolean): Unit = {
    Utils.TryWithRetry {
      logger.info(s"Updating published status of $name to $published")
      // Delete the old entry for query
      deleteQueryPublishedStatus(name, metaInfo.published)

      val filePath = if (published) {
        getQueryPublishedFolderPath(name)
      } else {
        getQueryUnpublishedFolderPath(name)
      }

      Utils.rewriteFile("", configuration, filePath)
    }
  }

  def deleteQueryPublishedStatus(name: String, published: Option[Boolean]): Unit = {
    Utils.TryWithRetry {
      logger.info(s"Deleting query published status of $name")
      val filePath = if (published == None || !published.get) {
        getQueryUnpublishedFolderPath(name)
      } else {
        getQueryPublishedFolderPath(name)
      }
      Utils.deleteFile(configuration, filePath)
    }
  }

  def deleteQuery(queryId: String) {
    logger.debug(s"Deleting query: $queryId")

    logger.debug(s"Deleting query state for: $queryId")
    var filePath = getQueryStateFilePath(queryId)
    Utils.deleteFile(configuration, filePath)

    logger.debug(s"Deleting query details for: $queryId")
    filePath = getQueryDetailsFilePath(queryId)
    Utils.deleteFile(configuration, filePath)

    val metaInfo = getMetaInfo(queryId)
    if (metaInfo.name != None && metaInfo.name.get != null) {
      deleteQueryName(metaInfo.name.get)
      if (metaInfo.published != None) {
        deleteQueryPublishedStatus(metaInfo.name.get, metaInfo.published)
      }
    }

    logger.debug(s"Deleting meta info for: $queryId")
    filePath = getQueryMetaInfoFilePath(queryId)
    Utils.deleteFile(configuration, filePath)

    logger.debug(s"Deleting query logs for: $queryId")
    filePath = getQueryLogsFolderPath(queryId)
    Utils.deleteFile(configuration, filePath)
  }

  def getQueryStateFilePath(queryId: String): String = {
    configuration.get(Utils.STATUS_FOLDER) + "/" + queryId
  }

  def getQueryDetailsFilePath(queryId: String): String = {
    configuration.get(Utils.DETAILS_FOLDER) + "/" + queryId
  }

  def getQueryMetaInfoFilePath(queryId: String): String = {
    configuration.get(Utils.METAINFO_FOLDER) + "/" + queryId
  }

  def getQueryLogsFolderPath(queryId: String): String = {
    configuration.get(Utils.LOGGING_FOLDER) + "/" + queryId
  }

  def getQueryNameFolderPath(name: String): String = {
    configuration.get(Utils.QUERY_NAME_FOLDER) + "/" + name
  }

  def getQueryPublishedFolderPath(name: String): String = {
    configuration.get(Utils.QUERY_PUBLISHED_FOLDER) + "/" + name
  }
  def getQueryUnpublishedFolderPath(name: String): String = {
    configuration.get(Utils.QUERY_UNPUBLISHED_FOLDER) + "/" + name
  }
}
