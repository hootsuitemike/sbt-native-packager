package com.typesafe.sbt
package packager
package universal

import sbt._

/** Helper methods to package up files into compressed archives. */
object Archives {
  
  /** Makes a zip file in the given target directory using the given name. */
  def makeZip(target: File, name: String, mappings: Seq[(File, String)]): File = {
    val zip = target / (name + ".zip")
    // TODO - If mappings already start with the given name, don't add it?
    val m2 = mappings map { case (f, p) => f -> (name +"/"+p) }
    ZipHelper.zip(m2, zip)
    zip
  }

  /** Makes a zip file in the given target directory using the given name. */
  def makeNativeZip(target: File, name: String, mappings: Seq[(File, String)]): File = {
    val zip = target / (name + ".zip")
    // TODO - If mappings already start with the given name, don't add it?
    val m2 = mappings map { case (f, p) => f -> (name +"/"+p) }
    ZipHelper.zipNative(m2, zip)
    zip
  }

  
  /** Makes a dmg file in the given target directory using the given name. 
   *  
   *  Note:  Only works on OSX
   */
  def makeDmg(target: File, name: String, mappings: Seq[(File, String)]): File = {
    val t = target / "dmg"
    val dmg = target / (name + ".dmg")
    if(!t.isDirectory) IO.createDirectory(t)
    val sizeBytes = mappings.map(_._1).filterNot(_.isDirectory).map(_.length).sum
    // We should give ourselves a buffer....
    val neededMegabytes = math.ceil((sizeBytes * 1.05) / (1024 * 1024)).toLong
    
    // Create the DMG file:
    Process(Seq(
        "hdiutil",
        "create", 
        "-megabytes", "%d" format neededMegabytes, 
        "-fs", "HFS+", 
        "-volname", name,  
        name), Some(target)).! match {
      case 0 => ()
      case n => sys.error("Error creating dmg: " + dmg + ". Exit code " + n)
    }
    
    // Now mount the DMG.
    val mountPoint = (t / name)
    if(!mountPoint.isDirectory) IO.createDirectory(mountPoint)
    val mountedPath = mountPoint.getAbsolutePath
    Process(Seq(
        "hdiutil", "attach", dmg.getAbsolutePath,
        "-readwrite",
        "-mountpoint",
        mountedPath), Some(target)).! match {
      case 0 => ()
      case n => sys.error("Unable to mount dmg: " + dmg + ". Exit code " + n)
    }

    // Now copy the files in
    val m2 = mappings map { case (f, p) => f -> (mountPoint / p) }
    IO.copy(m2)
    // Update for permissions
    for {
      (from, to) <- m2
      if from.canExecute()
    } to.setExecutable(true, true)
    
    // Now unmount
    Process(Seq("hdiutil", "detach", mountedPath), Some(target)).! match {
      case 0 => ()
      case n => sys.error("Unable to dismount dmg: " + dmg + ". Exit code " + n)
    }
    // Delete mount point
    IO.delete(mountPoint)
    dmg
  }
  
  /** GZips a file.  Returns the new gzipped file.
   * NOTE: This will 'consume' the input file.
   */
  def gzip(f: File): File = {
    val par = f.getParentFile
    Process(Seq("gzip", "-9", f.getAbsolutePath), Some(par)).! match {
      case 0 => ()
      case n => sys.error("Error gziping " + f + ". Exit code: " + n)
    }
    file(f.getAbsolutePath + ".gz")
  }
  
  /** xz compresses a file.  Returns the new xz compressed file.
   * NOTE: This will 'consume' the input file.
   */
  def xz(f: File): File = {
    val par = f.getParentFile
    Process(Seq("xz", "-9e", "-S", ".xz", f.getAbsolutePath), Some(par)).! match {
      case 0 => ()
      case n => sys.error("Error xz-ing " + f + ". Exit code: " + n)
    }
    file(f.getAbsolutePath + ".xz")
  }
  val makeTxz = makeTarball(xz, ".txz") _
  val makeTgz = makeTarball(gzip, ".tgz") _
  val makeTar = makeTarball(identity, ".tar") _
   
  /** Helper method used to construct tar-related compression functions. */
  def makeTarball(compressor: File => File, ext: String)(target: File, name: String, mappings: Seq[(File, String)]): File = {
    val relname = name
    val tarball = target / (name + ext)
    IO.withTemporaryDirectory { f =>
      val rdir = f / relname
      val m2 = mappings map { case (f, p) => f -> (rdir / name / p) }
      IO.copy(m2)
      // TODO - Is this enough?
      for(f <- (m2 map { case (_, f) => f } ); if f.getAbsolutePath contains "/bin/") {
        println("Making " + f.getAbsolutePath + " executable")
        f.setExecutable(true, false)
      }
      IO.createDirectory(tarball.getParentFile)      
      val distdir = IO.listFiles(rdir).headOption.getOrElse {
        sys.error("Unable to find tarball in directory: " + rdir.getAbsolutePath + ".\n  This could be an issue with the temporary filesystem used to create tarballs.")
      }
      val tmptar = f / (relname + ".tar")
      Process(Seq("tar", "-pcvf", tmptar.getAbsolutePath, distdir.getName), Some(rdir)).! match {
        case 0 => ()
        case n => sys.error("Error tarballing " + tarball + ". Exit code: " + n)
      }
      IO.copyFile(compressor(tmptar), tarball)
    }
    tarball
  }
}
