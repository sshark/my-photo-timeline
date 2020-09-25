package net.wiringbits.myphototimeline

case class IndexedFiles(data: Map[String, List[FileDetails]]) {

  def +(fileDetails: FileDetails): IndexedFiles = {
    fileDetails.hash.fold(this) { hashString =>
      val newData = fileDetails :: data.getOrElse(hashString, List.empty)
      copy(data = data + (hashString -> newData))
    }
  }

  def +(list: List[FileDetails]): IndexedFiles = {
    list.foldLeft(this)(_ + _)
  }

  def contains(hash: String): Boolean = {
    data.contains(hash)
  }

  def size: Int = data.size
}

object IndexedFiles {
  def empty: IndexedFiles = IndexedFiles(Map.empty)
}
