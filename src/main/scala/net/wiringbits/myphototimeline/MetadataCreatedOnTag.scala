package net.wiringbits.myphototimeline

import java.time.LocalDate

import cats.effect.Sync
import cats.syntax.all._
import com.drew.imaging.ImageMetadataReader

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Try, Success}

object MetadataCreatedOnTag {
  private val regex = """(\d\d\d\d).(\d\d).(\d\d).*""".r
  private val filenameRegex = """(\d\d\d\d)(\d\d)(\d\d)\D.*""".r

  def getCreationDateFromFilename[F[_]: Sync](sourceFile: os.Path): F[Option[LocalDate]] =
    for {
      filename <- Sync[F].delay(sourceFile.last)
      result <- Sync[F].fromTry(filename match {
        case filenameRegex(year, month, day) =>
          Try(Some(LocalDate.of(year.toInt, month.toInt, day.toInt))).recover { case _ => None }
        case _ => util.Success(None)
      })
    } yield result

  def getCreationDate[F[_]: Sync](sourceFile: os.Path): F[Option[LocalDate]] =
    (for {
      metadata <- Sync[F].delay(ImageMetadataReader.readMetadata(sourceFile.toIO))
      result <- Sync[F].delay(
        metadata.getDirectories.asScala
          .flatMap { d =>
            d.getTags.asScala
              .filter { t =>
                MetadataCreatedOnTag.names.contains(t.getTagName.toLowerCase)
              }
              .map(_.getDescription)
              .flatMap(Option.apply)
              .flatMap(MetadataCreatedOnTag.toDate)
          }
          .toList
          .headOption)
    } yield result).flatMap(d => getCreationDateFromFilenameIfNotFoundInMetadata(d, sourceFile))

/*
  {
    val metadata = ImageMetadataReader.readMetadata(sourceFile.toIO)
    Try(metadata.getDirectories.asScala.flatMap { d =>
      d.getTags.asScala
        .filter { t =>
          MetadataCreatedOnTag.names.contains(t.getTagName.toLowerCase)
        }
        .map(_.getDescription)
        .flatMap(Option.apply)
        .flatMap(MetadataCreatedOnTag.toDate)
    }.toList)
      .flatMap(xs => if (xs.isEmpty) Failure(new Throwable("No creation date found")) else Success(xs.head))
      .toOption
  }
*/

  private def getCreationDateFromFilenameIfNotFoundInMetadata[F[_]: Sync](
      date: Option[LocalDate],
      sourceFile: os.Path) =
    if (date.isDefined) Sync[F].pure(date) else getCreationDateFromFilename(sourceFile)


  def toDate(str: String): Option[LocalDate] =
    str match {
      case MetadataCreatedOnTag.regex(year, month, day) =>
        Try(LocalDate.of(year.toInt, month.toInt, day.toInt)).toOption
      case _ => None
    }

  //    val knownTags = List(
  //      "Date/Time", // 2014:08:31 14:31:24
  //      "Date/Time Original", // 2016:09:10 15:11:52
  //      "Date/Time Digitized", // 2016:09:10 15:11:52
  //      "File Modified Date", // Fri Jun 29 08:49:36 -06:00 2029
  //      "Profile Date/Time", // 1998:02:09 06:49:00
  //      "Date Created", // 2015:06:27
  //      "Digital Date Created" // 2015:06:27
  //      Creation Date - 2020-04-14T17:31:57-0600
  //    )
  val names: List[String] = List(
    "Date/Time",
    "Date/Time Original",
    "Date/Time Digitized",
    "Date Created",
    "Digital Date Created",
    "Creation Date",
    "Profile Date/Time"
  ).map(_.toLowerCase)
}
