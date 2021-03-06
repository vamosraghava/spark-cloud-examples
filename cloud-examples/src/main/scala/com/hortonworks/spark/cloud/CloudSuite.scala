/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hortonworks.spark.cloud

import java.io.{File, FileNotFoundException}
import java.net.{URI, URL}
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters._

import com.hortonworks.spark.cloud.s3.S3AConstants
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{CommonConfigurationKeysPublic, FSHelper, FileStatus, FileSystem, LocalFileSystem, Path}
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}

import org.apache.spark.{LocalSparkContext, SparkConf}

/**
 * A cloud suite.
 * Adds automatic loading of a Hadoop configuration file with login credentials and
 * options to enable/disable tests, and a mechanism to conditionally declare tests
 * based on these details
 */
abstract class CloudSuite extends FunSuite with CloudLogging with CloudTestKeys
    with LocalSparkContext with BeforeAndAfter with Matchers with TimeOperations
    with ObjectStoreOperations with Eventually with S3AConstants {

  import CloudSuite._

  /**
   * The test directory under `/spark-cloud`; derived from the classname
   * This path does not contain any FS binding.
   */
  protected val TestDir: Path = {
    new Path("/spark-cloud/" + this.getClass.getSimpleName)
  }

  /**
   * The configuration as loaded; may be undefined.
   */
  protected val testConfiguration: Option[Configuration] = loadConfiguration()

  /**
   * Accessor to the configuration, which must be non-empty.
   */
  protected def conf: Configuration = testConfiguration.getOrElse {
    throw new NoSuchElementException("No cloud test configuration provided")
  }

  /**
   * The filesystem.
   */
  private var _filesystem: Option[FileSystem] = None

  /**
   * Accessor for the filesystem.
   * @return the filesystem
   */
  protected def filesystem: FileSystem = _filesystem.get

  /**
   * Probe for the filesystem being defined.
   * @return
   */
  protected def isFilesystemDefined: Boolean = _filesystem.isDefined

  /**
   * URI to the filesystem.
   * @return the filesystem URI
   */
  protected def filesystemURI: URI = filesystem.getUri

  protected def cleanFSInTeardownEnabled = true

  /**
   * Determine the scale factor for larger tests.
   */
  private lazy val scaleSizeFactor = conf.getInt(SCALE_TEST_SIZE_FACTOR,
    SCALE_TEST_SIZE_FACTOR_DEFAULT)

  /**
   * Subclasses may override this for different or configurable test sizes
   * @return the number of entries in parallelized operations.
   */
  protected def testEntryCount: Int = 10 * scaleSizeFactor

  /** this system property is always set in a JVM */
  protected val localTmpDir: File = new File(System.getProperty("java.io.tmpdir", "/tmp"))
      .getCanonicalFile

  /**
   * Create a test path under the filesystem
   * @param fs filesystem
   * @param testname name of the test
   * @return a fully qualified path under the filesystem
   */
  protected def testPath(fs: FileSystem, testname: String): Path = {
    fs.makeQualified(new Path(TestDir, testname))
  }

  /**
   * A conditional test which is only executed when the suite is enabled,
   * and the `extraCondition` predicate holds.
   * @param name test name
   * @param detail detailed text for reports
   * @param extraCondition extra predicate which may be evaluated to decide if a test can run.
   * @param testFun function to execute
   */
  protected def ctest(
      name: String,
      detail: String = "",
      extraCondition: => Boolean = true)
      (testFun: => Unit): Unit = {
    if (enabled && extraCondition) {
      registerTest(name) {
        logInfo(s"$name\n$detail\n-------------------------------------------")
        testFun
      }
    } else {
      registerIgnoredTest(name) {
        testFun
      }
    }
  }

  /**
   * Update the filesystem; includes a validity check to ensure that the local filesystem
   * is never accidentally picked up.
   * @param fs new filesystem
   */
  protected def setFilesystem(fs: FileSystem): Unit = {
    if (fs.isInstanceOf[LocalFileSystem] || "file" == fs.getScheme) {
      throw new IllegalArgumentException("Test filesystem cannot be local filesystem")
    }
    _filesystem = Some(fs)
  }

  /**
   * Create a filesystem. This adds it as the `filesystem` field.
   * A new instance is created for every test, so that different configurations
   * can be used. If `registerToURI` is set, the instance is put into the FileSystem
   * map of (user, URI) to FileSystem instance, after which it will be picked up by
   * all in-VM code asking for a non-unique FS intance for that URI.
   * @param fsURI filesystem URI
   * @param registerToURI should the `Filesystem.get` binding for this URI be registered
   *                      to this instance?
   * @return the newly create FS.
   */
  protected def createFilesystem(fsURI: URI, registerToURI: Boolean = true): FileSystem = {
    val fs = FileSystem.newInstance(fsURI, conf)
    setFilesystem(fs)
    if (registerToURI) {
      FSHelper.addFileSystemForTesting(fsURI, conf, fs)
    }
    fs
  }

  /**
   * Clean up the filesystem if it is defined.
   */
  protected def cleanFilesystem(): Unit = {
    val target = s"${filesystem.getUri}$TestDir"
    if (filesystem.isInstanceOf[LocalFileSystem]) {
      logDebug(s"not touching local FS $filesystem")
    } else {
      note(s"Cleaning $target")
      if (filesystem.exists(TestDir) && !filesystem.delete(TestDir, true)) {
        logWarning(s"Deleting $target returned false")
      }
    }
  }

  /**
   * Teardown-time cleanup; exceptions are logged and not forwarded.
   */
  protected def cleanFilesystemInTeardown(): Unit = {
    try {
      if (cleanFSInTeardownEnabled) {
        cleanFilesystem()
      }
    } catch {
      case e: Throwable =>
        logInfo(s"During cleanup of filesystem: $e")
        logDebug(s"During cleanup of filesystem", e)
    }
  }

  /**
   * Is this test suite enabled?
   * The base class is enabled if the configuration file loaded; subclasses can extend
   * this with extra probes, such as for bindings to an object store.
   *
   * If this predicate is false, then tests defined in `ctest()` will be ignored
   * @return true if the test suite is enabled.
   */
  protected def enabled: Boolean = testConfiguration.isDefined

  /**
   * Get a required option; throw an exception if the key is missing or an empty string.
   * @param key the key to look up
   * @return the trimmed string value.
   */
  protected def requiredOption(key: String): String = {
    val v = conf.getTrimmed(key)
    require(v != null && !v.isEmpty, s"Unset/empty configuration option $key")
    v
  }

  /**
   * Override point for suites: a method which is called
   * in all the `newSparkConf()` methods.
   * This can be used to alter values for the configuration.
   * It is called before the configuration read in from the command line
   * is applied, so that tests can override the values applied in-code.
   *
   * @param sparkConf spark configuration to alter
   */
  protected def addSuiteConfigurationOptions(sparkConf: SparkConf): Unit = {
    sparkConf.setAll(MAPREDUCE_OPTIONS)
    sparkConf.setAll(ORC_OPTIONS)
    sparkConf.setAll(PARQUET_OPTIONS)
    hconf(sparkConf, BLOCK_SIZE, 1 * 1024 * 1024)
    hconf(sparkConf, MULTIPART_SIZE, MIN_PERMITTED_MULTIPART_SIZE)
    hconf(sparkConf, READAHEAD_RANGE, 128 * 1024)
    hconf(sparkConf, MIN_MULTIPART_THRESHOLD, MIN_PERMITTED_MULTIPART_SIZE)
    hconf(sparkConf, MIN_MULTIPART_THRESHOLD, MIN_PERMITTED_MULTIPART_SIZE)
    hconf(sparkConf, FAST_UPLOAD, "true")
    hconf(sparkConf, FAST_UPLOAD_BUFFER, FAST_UPLOAD_BUFFER_ARRAY)
  }

  /**
   * Create a `SparkConf` instance, using the current filesystem as the URI for the default FS.
   * All options loaded from the test configuration XML file will be added as hadoop options.
   * @return the configuration
   */
  def newSparkConf(): SparkConf = {
    require(isFilesystemDefined, "Not bonded to a test filesystem")
    newSparkConf(filesystemURI)
  }

  /**
   * Create a `SparkConf` instance, setting the default filesystem to that of `fsuri`.
   * All options loaded from the test configuration
   * XML file will be added as hadoop options.
   * @param fsuri the URI of the default filesystem
   * @return the configuration
   */
  def newSparkConf(fsuri: URI): SparkConf = {
    val sc = new SparkConf(false)
    addSuiteConfigurationOptions(sc)
    conf.asScala.foreach { e =>
      hconf(sc, e.getKey, e.getValue)
    }
    hconf(sc, CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, fsuri.toString)
    sc.setMaster("local")
    sc
  }

  /**
   * Creat a new spark configuration with the default FS that of the
   * path parameter supplied.
   * @param path path
   * @return a configuration with the default FS that of the path.
   */
  def newSparkConf(path: Path): SparkConf = {
    newSparkConf(path.getFileSystem(conf).getUri)
  }

  /**
   * Get the file status of a path.
   * @param path path to query
   * @return the status
   */
  def stat(path: Path): FileStatus = {
    filesystem.getFileStatus(path)
  }

  /**
   * Get the filesystem to a path; uses the current configuration.
   * @param path path to use
   * @return a (cached) filesystem.
   */
  def getFilesystem(path: Path): FileSystem = {
    FileSystem.newInstance(path.toUri, conf)
  }

  /**
   * Is a staging committer being used? If so, filesystem needs to be
   * set up right.
   *
   * @param config config to probe
   * @return true if this is staging. Not checked: does Hadoop support staging.
   */
  def isUsingStagingCommitter(config: Configuration): Boolean = {
    val committer = config.get(OUTPUTCOMMITTER_FACTORY_CLASS)
    committer.startsWith(STAGING)
  }
}

object CloudSuite extends CloudLogging with CloudTestKeys with S3AConstants {

  private val configLogged = new AtomicBoolean(false)

  /**
   * Locate a class/resource as a resource URL.
   * This does not attempt to load a class, merely verify that it is present
   * @param resource resource or path of class, such as
   *                 `org/apache/hadoop/fs/azure/AzureException.class`
   * @return the URL or null
   */
  def locateResource(resource: String): URL = {
    getClass.getClassLoader.getResource(resource)
  }

  /**
   * Load the configuration file from the system property `SYSPROP_CLOUD_TEST_CONFIGURATION_FILE`.
   * Throws FileNotFoundException if a configuration is named but not present.
   * @return the configuration
   */
  def loadConfiguration(): Option[Configuration] = {
    val filename = System.getProperty(SYSPROP_CLOUD_TEST_CONFIGURATION_FILE, "").trim
    logDebug(s"Configuration property = `$filename`")
    val config = new Configuration(true)
    if (filename != null && !filename.isEmpty && !UNSET_PROPERTY.equals(filename)) {
      val f = new File(filename)
      if (f.exists()) {
        if (!configLogged.getAndSet(true)) {
          logInfo(s"Loading configuration from $f")
        }
        config.addResource(f.toURI.toURL)
      } else {
        throw new FileNotFoundException(s"No file '$filename'" +
            s" in property $SYSPROP_CLOUD_TEST_CONFIGURATION_FILE")
      }
      // setup the committer from any property passed in
      val orig = config
        .get(OUTPUTCOMMITTER_FACTORY_CLASS, OUTPUTCOMMITTER_FACTORY_DEFAULT)
      val committer = System.getProperty(SYSPROP_CLOUD_TEST_COMMITTER, orig)
      if (committer != OUTPUTCOMMITTER_FACTORY_DEFAULT) {
        logInfo(s"Using committer $committer")
      }
      config.set(OUTPUTCOMMITTER_FACTORY_CLASS, committer)
      Some(config)
    } else {
      None
    }
  }

  /**
   * Overlay a set of system properties to a configuration, unless the key
   * is "(unset")
   * @param conf config to patch
   * @param keys list of system properties
   */
  def overlayConfiguration(conf: Configuration, keys: Seq[String]): Unit = {
    keys.foreach(key => if (System.getProperty(key) != UNSET_PROPERTY)
      conf.set(key, System.getProperty(key), "system property"))
  }

}
