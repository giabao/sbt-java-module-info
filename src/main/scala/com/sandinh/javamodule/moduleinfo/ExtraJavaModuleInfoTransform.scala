package com.sandinh.javamodule.moduleinfo

import org.jetbrains.annotations.Nullable
import org.objectweb.asm.{ClassWriter, Opcodes}
import sbt.*
import sbt.Keys.*
import sbt.io.{IO, Using}
import sbt.librarymanagement.Configurations.{Compile, Runtime}

import java.nio.file.Files
import java.util.jar.{JarEntry, JarInputStream, JarOutputStream, Manifest}
import java.util.zip.ZipException
import scala.io.Codec.UTF8
import scala.io.Source
import Utils.*

object ExtraJavaModuleInfoTransform {
  private val JarSignaturePath = "^META-INF/[^/]+\\.(SF|RSA|DSA|sf|rsa|dsa)$".r.pattern
  private val ServicesPrefix = "META-INF/services/"

  /** Convenient class to wrap providers and packages arguments */
  private case class PP(providers: Map[String, List[String]], packages: Set[String])
  private object PP { val empty: PP = PP(Map.empty, Set.empty) }

  def addAutomaticModuleName(
      artifacts: Classpath,
      originalJar: File,
      moduleJar: File,
      automaticModule: AutomaticModuleName
  ): Unit = originalJar.jarInputStream { jis =>
    val man = jis.getOrCreateManifest
    man.getMainAttributes.putValue("Automatic-Module-Name", automaticModule.moduleName)
    moduleJar.jarOutputStream(man) { jos =>
      val pp = copyAndExtractProviders(jis, jos, automaticModule.mergedJars.nonEmpty, PP.empty)
      mergeJars(artifacts, automaticModule, jos, pp)
    }
  }

  def addModuleDescriptor(
      args: ModuleInfoArgs,
      compileDepsMap: Map[String, Set[String]],
      runtimeDepsMap: Map[String, Set[String]],
      originalJar: File,
      moduleJar: File,
      moduleInfo: JModuleInfo,
  ): Unit = originalJar.jarInputStream { jis =>
    moduleJar.jarOutputStream(jis.getManifest) { jos =>
      var pp = copyAndExtractProviders(jis, jos, moduleInfo.mergedJars.nonEmpty, PP.empty)
      pp = mergeJars(args.artifacts, moduleInfo, jos, pp)
      jos.putNextEntry(new JarEntry("module-info.class"))
      val version = args.artifacts.collectFirst {
        case a if a.data == originalJar => a.get(moduleID.key).get.revision
      }.orNull
      jos.write(
        addModuleInfo(
          args,
          compileDepsMap,
          runtimeDepsMap,
          moduleInfo,
          pp.providers,
          version,
          if (moduleInfo.exportAll) pp.packages else Set.empty
        )
      )
      jos.closeEntry()
    }
  }

  private def copyAndExtractProviders(
      jis: JarInputStream,
      jos: JarOutputStream,
      willMergeJars: Boolean,
      pp: PP
  ): PP = {
    var PP(providers, packages) = pp
    jis.lazyList.foreach { jarEntry =>
      val content = IO.readBytes(jis)
      val entryName = jarEntry.getName
      val isFileInServicesFolder = entryName.startsWith(ServicesPrefix) && entryName != ServicesPrefix
      if (isFileInServicesFolder) {
        val key = entryName.substring(ServicesPrefix.length)
        val isServiceProviderFile = !key.contains("/") // ignore files in sub-folders
        if (isServiceProviderFile) {
          providers = providers.updated(
            key,
            providers.getOrElse(key, Nil) ++ extractImplementations(content)
          )
        }
      }
      if (
        !JarSignaturePath.matcher(entryName).matches && "META-INF/MANIFEST.MF" != jarEntry.getName &&
        (!willMergeJars || !isFileInServicesFolder) // service provider files will be merged later
      ) {
        jarEntry.setCompressedSize(-1)
        try {
          jos.putNextEntry(jarEntry)
          jos.write(content)
          jos.closeEntry()
        } catch {
          case e: ZipException =>
            if (!e.getMessage.startsWith("duplicate entry:")) throw new RuntimeException(e)
        }
        if (entryName.endsWith(".class")) {
          val i = entryName.lastIndexOf("/")
          if (i > 0) packages += entryName.substring(0, i)
        }
      }
    }
    PP(providers, packages)
  }

  private def extractImplementations(content: Array[Byte]) =
    Source
      .fromBytes(content)(UTF8)
      .getLines()
      .map(_.trim)
      .filter { line => line.nonEmpty && !line.startsWith("#") }
      .toList
      .distinct

  def addModuleInfo(info: JModuleInfo, mainClass: Option[String] = None): Array[Byte] = {
    val classWriter = new ClassWriter(0)
    classWriter.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
    val moduleVisitor = classWriter.visitModule(
      info.moduleName,
      if (info.openModule) Opcodes.ACC_OPEN else 0,
      info.moduleVersion
    )
    mainClass.foreach(moduleVisitor.visitMainClass)
    info.exports.map(toSlash).foreach(moduleVisitor.visitExport(_, 0))
    info.opens.map(toSlash).foreach(moduleVisitor.visitOpen(_, 0))
//    moduleVisitor.visitRequire("java.base", 0, null)
    info.requires.foreach { case (module, access) => moduleVisitor.visitRequire(module, access.code, null) }
    info.uses.map(toSlash).foreach(moduleVisitor.visitUse)
    info.providers.foreach { case (name, implementations) =>
      moduleVisitor.visitProvide(
        toSlash(name),
        implementations.map(toSlash)*
      )
    }
    moduleVisitor.visitEnd()
    classWriter.visitEnd()
    classWriter.toByteArray
  }

  private def addModuleInfo(
      args: ModuleInfoArgs,
      compileDepsMap: Map[String, Set[String]],
      runtimeDepsMap: Map[String, Set[String]],
      info: JModuleInfo,
      providers: Map[String, List[String]],
      @Nullable version: String,
      autoExportedPackages: Set[String],
  ): Array[Byte] = {
    def requires: Set[(String, Require)] = {
      val compileDeps = compileDepsMap.getOrElse(info.id, Set.empty)
      val runtimeDeps = runtimeDepsMap.getOrElse(info.id, Set.empty)
      if (
        compileDeps.isEmpty && runtimeDeps.isEmpty &&
        args.artifacts.forall(_.get(moduleID.key).get.jmodId != info.id)
      )
        throw new RuntimeException(
          s"[requires directives from metadata] Cannot find dependencies for '${info.moduleName}'. " +
            s"Are '${info.id}' the correct component coordinates?"
        )
      (for {
        ga <- compileDeps ++ runtimeDeps
        if !info.mergedJars.contains(ga)
      } yield {
        val moduleName = args.idToModuleName(ga)
        if (compileDeps.contains(ga) && runtimeDeps.contains(ga)) List(moduleName -> Require.Transitive)
        else if (runtimeDeps.contains(ga)) List(moduleName -> Require.Default)
        else if (compileDeps.contains(ga)) List(moduleName -> Require.Static)
        else Nil
      }).flatten
    }

    addModuleInfo(
      info.copy(
        moduleVersion = Option(info.moduleVersion).getOrElse(version),
        providers = providers.filterKeys(name => !info.ignoreServiceProviders.contains(name)),
        requires =
          if (!info.requireAll) info.requires
          else info.requires ++ requires
      )
    )
  }

  private def mergeJars(
      artifacts: Classpath,
      moduleSpec: ModuleSpec,
      outputStream: JarOutputStream,
      pp0: PP
  ): PP = {
    if (moduleSpec.mergedJars.isEmpty) return pp0
    var pp = pp0
    for (identifier <- moduleSpec.mergedJars) {
      artifacts.collectFirst {
        case a if a.get(moduleID.key).get.jmodId == identifier => a.data
      } match {
        case Some(mergeJarFile) =>
          mergeJarFile.jarInputStream { jis =>
            pp = copyAndExtractProviders(jis, outputStream, willMergeJars = true, pp)
          }
        case None => throw new RuntimeException("Jar not found: " + identifier)
      }
    }
    mergeServiceProviderFiles(outputStream, pp.providers)
    pp
  }

  private def mergeServiceProviderFiles(jos: JarOutputStream, providers: Map[String, List[String]]): Unit =
    providers.foreach { case (k, v) =>
      jos.putNextEntry(new JarEntry(ServicesPrefix + k))
      for (implementation <- v) {
        jos.write(implementation.getBytes)
        jos.write("\n".getBytes)
      }
      jos.closeEntry()
    }
}

private class ModuleInfoArgs(infos: Seq[ModuleSpec], jarTypes: Set[String], up: UpdateReport) {
  private lazy val allInfos = infos ++ artifacts.flatMap { a =>
    def id = a.get(moduleID.key).get.jmodId
    a.data.moduleName.toSeq.map(KnownModule(id, _))
  }
  def idToModuleName(id: String): String = allInfos
    .find(_.id == id)
    .fold(
      throw new RuntimeException(
        s"""The module name of the following component is not known: $id
         | - If it is not a module, patch it by adding 'JModuleInfo()' or 'AutomaticModuleName()' to the `moduleInfos`
         | - If it is already a module, make the module name known by adding 'KnownModule("$id", "<module name>")' to the `moduleInfos`
         |   Example: Search `KnownModule` at https://github.com/giabao/sbt-java-module-info/blob/master/src/sbt-test/all/all/build.sbt) """.stripMargin
      )
    )(_.moduleName)
  // @see managedClasspath
  lazy val artifacts: Classpath = up
    .filter(
      configurationFilter(Compile.name | Runtime.name) && artifactFilter(`type` = jarTypes)
    )
    .toSeq
    .map { case (_, module, _, file) =>
      Attributed(file)(AttributeMap.empty.put(moduleID.key, module))
    }
    .distinct
}
