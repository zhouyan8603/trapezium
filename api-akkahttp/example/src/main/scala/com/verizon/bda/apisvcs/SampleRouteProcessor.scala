package com.verizon.bda.apisvcs

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.util

import com.verizon.bda.apiservices.ApiSvcProcessor
import com.verizon.logger.BDALoggerFactory

import scala.concurrent.ExecutionContext

/**
  * Created by chundch on 4/27/17.
  */

class SampleRouteProcessor(implicit val executionContext: ExecutionContext)
                                                  extends ApiSvcProcessor {

  private val logger = BDALoggerFactory.getLogger(this.getClass)

  import akka.http.scaladsl.model.ContentType
  def conetnType: ContentType = null
  /**
    * Implementation of Api Processor
    * @param path
    * @param pathVersion
    * @param headerData
    * @param requestData
    * @return process result as string
    */

  override def process(path: String, pathVersion: String, headerData : util.Map[String, String],
                       requestData : Any): String = {
    logger.info("Reading the data from http request stream")
    var responsedata = ""
    try {
    responsedata = dataAsStringFromInputStream(requestData.asInstanceOf[InputStream])
    logger.info("done reading the data")
    } catch {
      case e : Exception => {
        logger.error("Failed to process the request" , e)
        throw e
      }
    }
    responsedata
  }

  /**
    * Helper method to read data from http request stream
    * @param dataInputStream
    * @return request data as string
    */

  def dataAsStringFromInputStream(dataInputStream : InputStream ) : String = {



    val starttime = System.currentTimeMillis()
    var dataString = ""

    val inputStreamReader = new InputStreamReader(dataInputStream)
    val bufferedReader = new BufferedReader(inputStreamReader)
    logger.debug("Created buffered reader for http request input stream")
    try {

      dataString = Iterator continually bufferedReader.readLine takeWhile (_ != null) mkString

    } catch {
      case e : Exception => {
        logger.error("Failed to read data from http request" , e )
        throw e
      }
    } finally {

      if (bufferedReader != null ){
        try
        {
          bufferedReader.close()
        } catch {
          case e : Exception => {
            logger.error("failed to close buffered reader" , e )
          }
        }
      }
      if (inputStreamReader != null ) {
        try {
          inputStreamReader.close()
        } catch {
          case e: Exception => {
             logger.error("failed to close reader", e)
          }
        }
      }
      if (dataInputStream != null) {
        try {
          dataInputStream.close()
        } catch {
          case e: Exception => {
            logger.error("falied to close input stream", e)
          }
        }
      }
    }
    logger.info("extracted message from request input stream " +
      "Time taken for parsing in milliseconds " + (System.currentTimeMillis() - starttime))
    dataString

  }
}