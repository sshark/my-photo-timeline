package net.wiringbits.myphototimeline

import java.time.LocalDate

sealed trait FileInfo
case class FileDetails(source: os.Path, createdOn: LocalDate, hash: String) extends FileInfo
case class PathOnly(source: os.Path) extends FileInfo
