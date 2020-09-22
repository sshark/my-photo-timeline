lazy val root = (project in file("."))
  .enablePlugins(GitVersioning, BuildInfoPlugin, NativeImagePlugin)
  .settings(
    name := """my-photo-timeline""",
    organization := "net.wiringbits",
    scalaVersion := "2.13.3",
    fork in Test := true,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.baseVersion, git.gitHeadCommit),
    buildInfoPackage := "net.wiringbits.myphototimeline",
    buildInfoUsePackageAsPath := true,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.7.1",
      "com.lihaoyi" %% "fansi" % "0.2.7",
      "com.google.guava" % "guava" % "28.0-jre",
      "com.drewnoakes" % "metadata-extractor" % "2.14.0",
      "com.monovore" %% "decline-effect" % "1.3.0",
      "co.fs2" %% "fs2-core" % "2.4.0"
    ),
    Compile / mainClass := Some("net.wiringbits.myphototimeline.Main"),
    nativeImageOptions ++= List("--no-fallback")
  )
