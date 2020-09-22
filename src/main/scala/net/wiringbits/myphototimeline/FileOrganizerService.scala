package net.wiringbits.myphototimeline

import java.time.LocalDate

import cats.effect.Sync
import com.google.common.hash.Hashing
import com.google.common.io.Files
import cats.syntax.all._
import os.Path

import scala.annotation.tailrec

object FileOrganizerService {

  def computeHash(source: os.Path): String =
    Files.asByteSource(source.toIO).hash(Hashing.sha256()).toString

  def cleanEmptyDirectories[F[_]: Sync](root: os.Path): F[Unit] =
    Sync[F].delay(
      os.walk(root)
        .filter(os.isDir)
        .sortBy(_.segmentCount)
        .reverse
        .foreach { dir =>
          if (os.list(dir).isEmpty) {
            os.remove(dir)
          }
        })

  def load[F[_]: Sync](root: String)(trackProgress: (Int, Int) => F[Unit]): F[(IndexedFiles, List[os.Path])] =
    for {
      osRoot <- Sync[F].delay(os.Path(root))
      input <- Sync[F].delay(if (os.exists(osRoot)) os.walk(osRoot).filter(os.isFile) else List.empty)
      total = input.size
      leftAndRight <-
        fs2.Stream.emits(input.zipWithIndex)
          .flatMap {
            case (sourceFile, index) =>
              fs2.Stream.eval(trackProgress(index, total)).flatMap(_ =>
              fs2.Stream.eval(MetadataCreatedOnTag
                .getCreationDate(sourceFile))
                .map(_.fold(sourceFile.asLeft[FileDetails])(createdOn => FileDetails(sourceFile, createdOn,  computeHash(sourceFile)).asRight[Path])))
          }.compile.toList.map(_.partition(_.isLeft))
      (left, right) = leftAndRight
      invalid = left.flatMap(_.left.toOption)
      valid = right.flatMap(_.toOption)
      result <- Sync[F].delay(valid.foldLeft(IndexedFiles.empty)(_ + _) -> invalid)
    } yield result

  def organizeByDate[F[_]: Sync](destinationDirectory: os.Path, sourceFile: os.Path, createdOn: LocalDate): F[Unit] = {
    val year = createdOn.getYear.toString
    val monthName = createdOn.getMonth.toString.toLowerCase
    val monthNumber = createdOn.getMonthValue
    val month = "%2d-%s".format(monthNumber, monthName).replace(" ", "0")
    for {
      parent <- Sync[F].delay(destinationDirectory / year / month)
      destinationFile <- getAvailablePath(parent, sourceFile.last)
      _ <- Sync[F].delay(os.move(sourceFile, destinationFile, replaceExisting = false, createFolders = true))
      _ <- Sync[F].delay(destinationFile.toIO.setLastModified(createdOn.toEpochDay))
    } yield ()
  }

  def safeMove[F[_]: Sync](destinationDirectory: os.Path, sourceFile: os.Path): F[Unit] =
    for {
      destinationFile <- getAvailablePath(destinationDirectory, sourceFile.last)
      _ <- Sync[F].delay(os.move(sourceFile, destinationFile, replaceExisting = false, createFolders = true))
    } yield ()
  
  def getAvailablePath[F[_]: Sync](parent: os.Path, name: String, suffix: Int = 0): F[os.Path] =
    for {
      actualName <- Sync[F].point(if (suffix == 0) name else s"${name}_($suffix)")
      path <- Sync[F].delay(parent / actualName)
      exists <- Sync[F].delay(os.exists(path))
      result <- if (exists) getAvailablePath(parent, name, suffix + 1) else Sync[F].point(path)
    } yield result
}
