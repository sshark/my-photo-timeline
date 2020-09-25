package net.wiringbits.myphototimeline

import java.time.LocalDate

case class FileDetails(source: os.Path, createdOn: Option[LocalDate] = None, hash: Option[String] = None)
