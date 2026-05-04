// Share the codegen plugin source into the meta-build so that the examples module
// can use ApplyConfigGenerator via sourceGenerators without publishing the plugin first.
Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "sbt-plugin" / "src" / "main" / "scala"
