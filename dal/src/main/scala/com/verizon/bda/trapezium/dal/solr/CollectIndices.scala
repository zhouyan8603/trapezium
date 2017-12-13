package com.verizon.bda.trapezium.dal.solr

import java.io.{File, InputStream}
import java.net.URI

import com.jcraft.jsch.{ChannelExec, JSch, Session}
import com.verizon.bda.trapezium.dal.exceptions.SolrOpsException
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.log4j.Logger

import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, Map => MMap}
import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.mutable.ParArray
import scala.concurrent.Future
import scala.reflect.ClassTag

/**
  * Created by venkatesh on 7/10/17.
  */

class CollectIndices {
  val log = Logger.getLogger(classOf[CollectIndices])
  var session: Session = _

  def initSession(host: String, user: String, privateKey: String = "~/.ssh/id_rsa") {
    val jsch = new JSch
    jsch.addIdentity(privateKey)
    session = jsch.getSession(user, host, 22)
    session.setConfig("StrictHostKeyChecking", "no")
    session.setTimeout(1000000)
    log.info(s"making ssh session with ${user}@${host}")

    session.connect()
  }

  def disconnectSession() {
    session.disconnect
  }

  def runCommand(command: String, retry: Boolean, retryCount: Int = 5): Int = {
    var code = -1
    var retries = retryCount
    try {
      do {
        val channel: ChannelExec = getConnectedChannel(command)
        if (channel == null) {
          throw new SolrOpsException(s"could not execute command: $command on ${session.getHost}")
        }
        log.info(s"running command : ${command} in ${session.getHost}" +
          s" with user ${session.getUserName}")
        val in: InputStream = channel.getInputStream
        code = printResult(in, channel)
        log.info(s" command : ${command} \n completed on ${session.getHost}" +
          s" with user ${session.getUserName} with exit code:$code")
        if (code == 0 && retries != retryCount) {
          log.info(s" command : ${command} \n completed on ${session.getHost}" +
            s" with user ${session.getUserName} with exit code:$code on retry")
        }
        retries = retries - 1
      }
      while (code != 0 && retries > 0)
      if (code != 0) {
        throw new SolrOpsException(s"could not execute $command has" +
          s" returned $code ${session.getHost}")
      }
    } catch {
      case e: Exception => {
        log.warn(s"Has problem running the command :$command", e)
        return code
      }
    }
    code
  }

  def getConnectedChannel(command: String, retry: Int = 5): ChannelExec = {
    if (retry > 0) {
      try {
        val channel: ChannelExec = session.openChannel("exec").asInstanceOf[ChannelExec]
        channel.setInputStream(null)
        channel.setCommand(command)
        channel.connect
        return channel
      } catch {
        case e: Exception => {
          log.warn(s"Has problem opening the channel for command :$command \n" +
            s"and retrying to open channel on ${session.getHost}", e)
          Thread.sleep(10000)

          getConnectedChannel(command, retry - 1)
        }
      }
    }
    else {
      null
    }

  }

  def printResult(in: InputStream, channel: ChannelExec): Int = {
    val tmp = new Array[Byte](1024)
    val strBuilder = new StringBuilder
    var continueLoop = true
    while (continueLoop) {
      while (in.available > 0) {
        val i = in.read(tmp, 0, 1024)
        if (i < 0) continueLoop = false
        strBuilder.append(new String(tmp, 0, i))
      }
      if (continueLoop && channel.isClosed) {
        log.warn("exit-status:" + channel.getExitStatus)
        log.warn("with error stream as " + channel.getErrStream.toString)
        continueLoop = false
      }
    }
    log.warn(strBuilder.toString)
    channel.getExitStatus
  }


}

object CollectIndices {
  val log = Logger.getLogger(classOf[CollectIndices])
  val machineMap: MMap[String, CollectIndices] = new mutable.HashMap[String, CollectIndices]()

  def getMachine(host: String, solrNodeUser: String, machinePrivateKey: String): CollectIndices = {
    if (machineMap.contains(host)) {
      machineMap(host)
    } else {
      val scpHost = new CollectIndices
      if (machinePrivateKey == null) {
        scpHost.initSession(host, solrNodeUser)
      } else {
        scpHost.initSession(host, solrNodeUser, machinePrivateKey)
      }
      machineMap(host) = scpHost
      scpHost
    }
  }

  //  def moveFilesFromHdfsToLocalAllAtOnce(solrMap: Map[String, String],
  //                                        indexFilePath: String,
  //                                        movingDirectory: String, coreMap: Map[String, String]):
  //  Map[String, ListBuffer[(String, String)]] = {
  //    "for element in ${array[@]}  " +
  //      "do  " +
  //      "echo $element   " +
  //      "done"
  //    coreMap.foreach((file: String, node: String) => {
  //
  //    })
  //  }

  def moveFilesFromHdfsToLocal(solrMap: Map[String, String],
                               indexFilePath: String,
                               movingDirectory: String, coreMap: Map[String, String])
  : Map[String, ListBuffer[(String, String)]] = {
    log.info("inside move files")
    val solrNodeUser = solrMap("solrUser")
    val machinePrivateKey = solrMap.getOrElse("machinePrivateKey", null)
    val solrNodes = new ListBuffer[CollectIndices]

    val shards = coreMap.keySet.toArray
    var partFileMap = MMap[String, ListBuffer[String]]()
    //    coreMap.toList.foreach((shardId: String, host: String) => {
    //      val tmp = shardId.split("_")
    //
    //    })
    for ((shardId, host) <- coreMap) {
      val tmp = shardId.split("_")
      val folderPrefix = solrMap("folderPrefix").stripSuffix("/")
      val partFile = folderPrefix + (tmp(tmp.length - 2).substring(5).toInt - 1)
      if (partFileMap.contains(host)) {
        partFileMap(host).append((partFile))
      } else {
        partFileMap(host) = new ListBuffer[String]
        partFileMap(host).append((partFile))
      }
    }
    var array: ListBuffer[(CollectIndices, String)] = new ListBuffer[(CollectIndices, String)]
    for ((host: String, partFileList: ListBuffer[String]) <- partFileMap) {
      val machine: CollectIndices = getMachine(host.split(":")(0), solrNodeUser, machinePrivateKey)
      val command = s"partFiles=(" + partFileList.mkString("\t") + ")" +
        ";for partFile in ${partFiles[@]};" +
        " do " +
        "hdfs dfs -copyToLocal " + indexFilePath + "$partFile  " + movingDirectory + " ;" +
        " done ;chmod  -R 777  " + movingDirectory
      log.info(s"$machine running $command")
      array.append((machine, command))
    }

    val f = Future {
      sleep(Random.nextInt(500))
      42
    }
    println("before onComplete")
    f.onComplete {
      case Success(value) => println(s"Got the callback, meaning = $value")
      case Failure(e) => e.printStackTrace
    }
    val pc1: ParArray[(CollectIndices, String)] = ParArray
      .createFromCopy(array.toArray)
    pc1.tasksupport = new ForkJoinTaskSupport(new scala.concurrent
    .forkjoin.ForkJoinPool(array.length))
    pc1.map(p => {
      p._1.runCommand(p._2, true)
    })
    return null
  }

  def parallelSshFire(sshSequence: Array[(CollectIndices, String, String, String)],
                      directory: String,
                      coreMap: Map[String, String]): MMap[String, ListBuffer[(String, String)]] = {
    var map = MMap[String, ListBuffer[(String, String)]]()

    val pc1: ParArray[(CollectIndices, String, String, String)] =
      ParArray.createFromCopy(sshSequence)

    pc1.tasksupport = new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(8))
    pc1.map(p => {
      p._1.runCommand(p._2, true)
    })
    for ((machine, command, partFile, shard) <- sshSequence) {

      val host = coreMap(shard)
      val fileName = partFile
      if (map.contains(host)) {
        map(host).append((s"${directory}$fileName", shard))
      } else {
        map(host) = new ListBuffer[(String, String)]
        map(host).append((s"${directory}$fileName", shard))
      }
    }
    map
  }

  //
  // def getWellDistributed[A: ClassTag, B:
  // ClassTag](map: Map[A, Array[B]], size: Int): Array[B] = {
  //    val numMachines = map.keySet.size
  //    val map1 = new java.util.HashMap[A, Int]
  //    val lb = new ListBuffer[B]
  //    val li = map.keySet.toList
  //    var recordCount = 0
  //    var tempcount = 0
  //    var name: A = map.head._1
  //    var j = 0
  //    for (i <- 0 until size) {
  //      j = i
  //      do {
  //        name = li(j % numMachines)
  //        if (!map1.containsKey(name)) {
  //          map1.put(name, 0)
  //        }
  //        tempcount = map1.get(name)
  //        recordCount = map(name).size
  //        j = j + 1
  //      }
  //      while (recordCount <= tempcount && (j - i) != numMachines)
  //      if ((j - i) != numMachines) {
  //        lb.append(map.get(name).get(tempcount))
  //      }
  //
  //      map1.put(name, tempcount + 1)
  //    }
  //    lb.toArray
  //
  //  }

  def getHdfsList(nameNode: String, folderPrefix: String, indexFilePath: String): Array[String] = {
    val configuration: Configuration = new Configuration()
    configuration.set("fs.hdfs.impl", classOf[org.apache.hadoop.hdfs.DistributedFileSystem].getName)
    // 2. Get the instance of the HDFS
    val hdfs = FileSystem.get(new URI(s"hdfs://${nameNode}"), configuration)
    // 3. Get the metadata of the desired directory
    val fileStatus = hdfs.listStatus(new Path(s"hdfs://${nameNode}" + indexFilePath))
    // 4. Using FileUtil, getting the Paths for all the FileStatus
    val paths = FileUtil.stat2Paths(fileStatus)
    paths.map(_.toString).filter(p => p.contains(folderPrefix))
  }

}
