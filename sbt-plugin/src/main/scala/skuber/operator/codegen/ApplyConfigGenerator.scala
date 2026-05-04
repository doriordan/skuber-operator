package skuber.operator.codegen

import sbt._
import java.io.File

object ApplyConfigGenerator {

  // Escape helpers — avoid \" and \\ inside s"..." interpolation (Scala 2.12 parser issue)
  private val DQ = "\""   // double quote character
  private val BS = "\\"   // backslash character

  private def jsPathField(name: String): String = "(JsPath " + BS + " " + DQ + name + DQ + ")"
  private def strLit(value: String): String = DQ + value + DQ

  // ==================== Data Model ====================

  sealed trait FieldType {
    def render: String
  }

  case class SimpleType(name: String) extends FieldType {
    def render: String = name
  }

  case class GenericType(name: String, args: List[FieldType]) extends FieldType {
    def render: String = s"$name[${args.map(_.render).mkString(", ")}]"
  }

  case class FieldDef(name: String, fieldType: FieldType, hasDefault: Boolean)

  case class CaseClassDef(name: String, fields: List[FieldDef])

  case class EnumDef(name: String)

  case class CustomResourceInfo(
    objectName: String,
    packageName: String,
    group: String,
    version: String,
    kind: String,
    caseClasses: List[CaseClassDef],
    enums: List[EnumDef],
    hasStatus: Boolean,
    sourceImports: List[String]
  )

  // ==================== Source Parsing ====================

  def parseSourceFile(file: File): Option[CustomResourceInfo] = {
    val content = IO.read(file)

    if (!content.contains("@customResource")) return None
    if (content.contains("@noApplyConfigGen")) return None

    val packagePattern = """package\s+([\w.]+)""".r
    val packageName = packagePattern.findFirstMatchIn(content).map(_.group(1)).getOrElse("")

    val annoIdx = content.indexOf("@customResource(")
    if (annoIdx < 0) return None

    val (annoContent, annoEndIdx) = extractBalanced(content, annoIdx + "@customResource".length)

    val params = parseAnnotationParams(annoContent)
    val group = params.getOrElse("group", "")
    val version = params.getOrElse("version", "")
    val kind = params.getOrElse("kind", "")

    if (group.isEmpty || version.isEmpty || kind.isEmpty) return None

    val afterAnno = content.substring(annoEndIdx)
    val objPattern = """object\s+(\w+)\s+extends\s+(CustomResourceDef|CustomResourceSpecDef)""".r
    val objMatch = objPattern.findFirstMatchIn(afterAnno)
    if (objMatch.isEmpty) return None

    val objectName = objMatch.get.group(1)
    val hasStatus = objMatch.get.group(2) == "CustomResourceDef"

    val objBodyStart = content.indexOf(objectName, annoEndIdx)
    val bodyContent = content.substring(objBodyStart)

    val caseClasses = parseCaseClasses(bodyContent)
    val enums = parseEnums(bodyContent)

    val importPattern = """(?m)^import\s+.+$""".r
    val sourceImports = importPattern.findAllIn(content).toList

    Some(CustomResourceInfo(objectName, packageName, group, version, kind, caseClasses, enums, hasStatus, sourceImports))
  }

  private def extractBalanced(content: String, startIdx: Int): (String, Int) = {
    var i = startIdx
    while (i < content.length && content.charAt(i) != '(') i += 1
    if (i >= content.length) return ("", i)

    var depth = 0
    val inner = new StringBuilder
    while (i < content.length) {
      val c = content.charAt(i)
      if (c == '(') {
        if (depth > 0) inner.append(c)
        depth += 1
      } else if (c == ')') {
        depth -= 1
        if (depth == 0) return (inner.toString, i + 1)
        inner.append(c)
      } else {
        if (depth > 0) inner.append(c)
      }
      i += 1
    }
    (inner.toString, i)
  }

  private def parseAnnotationParams(content: String): Map[String, String] = {
    val params = scala.collection.mutable.Map.empty[String, String]
    val kvPattern = """(\w+)\s*=\s*(?:"([^"]*)"|([\w.]+))""".r
    for (m <- kvPattern.findAllMatchIn(content)) {
      val key = m.group(1)
      val value = if (m.group(2) != null) m.group(2) else m.group(3)
      params(key) = value
    }
    params.toMap
  }

  private def parseCaseClasses(content: String): List[CaseClassDef] = {
    val results = List.newBuilder[CaseClassDef]
    val pattern = """case\s+class\s+(\w+)\s*\(""".r

    for (m <- pattern.findAllMatchIn(content)) {
      val name = m.group(1)
      val parenStart = m.end - 1
      val (fieldsContent, _) = extractBalanced(content, parenStart)
      val fields = parseFields(fieldsContent)
      results += CaseClassDef(name, fields)
    }
    results.result()
  }

  private def parseEnums(content: String): List[EnumDef] = {
    val pattern = """enum\s+(\w+)\s*:""".r
    pattern.findAllMatchIn(content).map(m => EnumDef(m.group(1))).toList
  }

  private def stripComments(s: String): String = {
    val noBlock = s.replaceAll("""/\*[\s\S]*?\*/""", "")
    noBlock.replaceAll("//[^\n]*", "")
  }

  private def parseFields(content: String): List[FieldDef] = {
    val cleaned = stripComments(content).trim
    if (cleaned.isEmpty) return Nil

    val parts = splitTopLevel(cleaned, ',')
    parts.flatMap { part =>
      val trimmed = part.trim
      if (trimmed.isEmpty) None
      else {
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx < 0) None
        else {
          val name = trimmed.substring(0, colonIdx).trim
          val afterColon = trimmed.substring(colonIdx + 1).trim

          var depth = 0
          var equalsIdx = -1
          var i = 0
          while (i < afterColon.length && equalsIdx < 0) {
            afterColon.charAt(i) match {
              case '(' | '[' => depth += 1
              case ')' | ']' => depth -= 1
              case '=' if depth == 0 => equalsIdx = i
              case _ =>
            }
            i += 1
          }

          val hasDefault = equalsIdx >= 0
          val typeStr = (if (hasDefault) afterColon.substring(0, equalsIdx) else afterColon).trim
          val (fieldType, _) = parseType(typeStr)
          Some(FieldDef(name, fieldType, hasDefault))
        }
      }
    }
  }

  private def splitTopLevel(s: String, sep: Char): List[String] = {
    val parts = List.newBuilder[String]
    var depth = 0
    val current = new StringBuilder
    for (c <- s) {
      if (c == '(' || c == '[') depth += 1
      else if (c == ')' || c == ']') depth -= 1

      if (c == sep && depth == 0) {
        parts += current.toString
        current.clear()
      } else {
        current.append(c)
      }
    }
    val last = current.toString
    if (last.trim.nonEmpty) parts += last
    parts.result()
  }

  private def parseType(s: String): (FieldType, String) = {
    val trimmed = s.trim
    val nameEnd = trimmed.indexWhere(c => !c.isLetterOrDigit && c != '.' && c != '_')
    val (name, rest) = if (nameEnd < 0) (trimmed, "")
                        else (trimmed.substring(0, nameEnd), trimmed.substring(nameEnd).trim)

    if (rest.startsWith("[")) {
      val (args, rest2) = parseTypeArgs(rest.substring(1))
      (GenericType(name, args), rest2)
    } else {
      (SimpleType(name), rest)
    }
  }

  private def parseTypeArgs(s: String): (List[FieldType], String) = {
    val args = List.newBuilder[FieldType]
    var remaining = s.trim
    while (remaining.nonEmpty && !remaining.startsWith("]")) {
      val (arg, rest) = parseType(remaining)
      args += arg
      remaining = rest.trim
      if (remaining.startsWith(",")) remaining = remaining.substring(1).trim
    }
    if (remaining.startsWith("]")) remaining = remaining.substring(1).trim
    (args.result(), remaining)
  }

  // ==================== Type Transformation ====================

  private val containerTypes = Set("List", "Set", "Seq", "Vector")

  def transformFieldType(ft: FieldType, caseClassNames: Set[String], enumNames: Set[String], objectName: String): FieldType = ft match {
    case GenericType("Option", List(inner)) =>
      GenericType("Option", List(transformInnerType(inner, caseClassNames, enumNames, objectName)))

    case SimpleType(name) if caseClassNames.contains(name) =>
      GenericType("Option", List(SimpleType(name + "ApplyConfig")))

    case SimpleType(name) if enumNames.contains(name) =>
      GenericType("Option", List(SimpleType(objectName + "." + name)))

    case SimpleType(name) =>
      GenericType("Option", List(SimpleType(name)))

    case GenericType(container, List(inner)) if containerTypes.contains(container) =>
      GenericType("Option", List(GenericType(container, List(transformInnerType(inner, caseClassNames, enumNames, objectName)))))

    case GenericType("Map", List(key, value)) =>
      GenericType("Option", List(GenericType("Map", List(key, transformInnerType(value, caseClassNames, enumNames, objectName)))))

    case other =>
      GenericType("Option", List(other))
  }

  def transformInnerType(ft: FieldType, caseClassNames: Set[String], enumNames: Set[String], objectName: String): FieldType = ft match {
    case SimpleType(name) if caseClassNames.contains(name) =>
      SimpleType(name + "ApplyConfig")

    case SimpleType(name) if enumNames.contains(name) =>
      SimpleType(objectName + "." + name)

    case GenericType("Option", List(inner)) =>
      GenericType("Option", List(transformInnerType(inner, caseClassNames, enumNames, objectName)))

    case GenericType(container, List(inner)) if containerTypes.contains(container) =>
      GenericType(container, List(transformInnerType(inner, caseClassNames, enumNames, objectName)))

    case GenericType("Map", List(key, value)) =>
      GenericType("Map", List(key, transformInnerType(value, caseClassNames, enumNames, objectName)))

    case other => other
  }

  def unwrapOption(ft: FieldType): FieldType = ft match {
    case GenericType("Option", List(inner)) => inner
    case other => other
  }

  // ==================== Topological Sort ====================

  def topoSort(classes: List[CaseClassDef], caseClassNames: Set[String]): List[CaseClassDef] = {
    def extractRefs(ft: FieldType): Set[String] = ft match {
      case SimpleType(name) if caseClassNames.contains(name) => Set(name)
      case GenericType(_, args) => args.flatMap(extractRefs).toSet
      case _ => Set.empty
    }

    def getDeps(cc: CaseClassDef): Set[String] =
      cc.fields.flatMap(f => extractRefs(f.fieldType)).toSet - cc.name

    var remaining = classes
    var sorted = List.empty[CaseClassDef]
    var sortedNames = Set.empty[String]

    while (remaining.nonEmpty) {
      val (ready, notReady) = remaining.partition(cc => getDeps(cc).subsetOf(sortedNames))
      if (ready.isEmpty && notReady.nonEmpty)
        sys.error(s"Circular dependency among: ${notReady.map(_.name).mkString(", ")}")
      sorted = sorted ++ ready
      sortedNames = sortedNames ++ ready.map(_.name)
      remaining = notReady
    }
    sorted
  }

  // ==================== Enum Collection ====================

  def collectUsedEnums(classes: List[CaseClassDef], enumNames: Set[String]): Set[String] = {
    def extractEnumRefs(ft: FieldType): Set[String] = ft match {
      case SimpleType(name) if enumNames.contains(name) => Set(name)
      case GenericType(_, args) => args.flatMap(extractEnumRefs).toSet
      case _ => Set.empty
    }
    classes.flatMap(_.fields).flatMap(f => extractEnumRefs(f.fieldType)).toSet
  }

  // ==================== Code Generation ====================

  def generateApplyConfig(info: CustomResourceInfo): String = {
    val caseClassNames = info.caseClasses.map(_.name).toSet
    val enumNames = info.enums.map(_.name).toSet

    val sb = new StringBuilder

    // Package declaration
    sb.append(s"package ${info.packageName}\n\n")

    // Standard imports
    sb.append("import play.api.libs.json.*\n")
    sb.append("import play.api.libs.functional.syntax.*\n")
    sb.append("import skuber.model.ac.{ApplyConfiguration, ObjectMetaApplyConfig}\n")
    sb.append(s"import ${info.objectName}.given\n")
    sb.append("import skuber.operator.crd.CommonFormats.given\n")

    // Copy relevant source imports
    for (imp <- info.sourceImports) {
      if (!imp.contains("customResource") &&
          !imp.contains("CustomResourceDef") &&
          !imp.contains("CustomResourceSpecDef") &&
          !imp.contains("operator.crd.Scope") &&
          !imp.contains("scala.annotation.experimental")) {
        sb.append(imp).append('\n')
      }
    }

    sb.append('\n')
    sb.append(s"object ${info.objectName}ApplyConfigs:\n")

    // Generate Writes for enum types used in fields
    val usedEnums = collectUsedEnums(info.caseClasses, enumNames)
    for (enumName <- usedEnums) {
      val qualifiedName = info.objectName + "." + enumName
      sb.append(s"\n  private given Writes[$qualifiedName] = Writes(e => JsString(e.toString))\n")
    }

    // Sort case classes topologically
    val sorted = topoSort(info.caseClasses, caseClassNames)

    // Generate nested apply configs first (not Spec/Status), then Spec, then Status
    val nested = sorted.filter(cc => cc.name != "Spec" && cc.name != "Status")
    val specOpt = sorted.find(_.name == "Spec")
    val statusOpt = if (info.hasStatus) sorted.find(_.name == "Status") else None

    for (cc <- nested) {
      sb.append('\n')
      sb.append(generateNestedApplyConfig(cc, caseClassNames, enumNames, info.objectName))
    }

    specOpt.foreach { cc =>
      sb.append('\n')
      sb.append(generateNestedApplyConfig(cc, caseClassNames, enumNames, info.objectName))
    }

    statusOpt.foreach { cc =>
      sb.append('\n')
      sb.append(generateNestedApplyConfig(cc, caseClassNames, enumNames, info.objectName))
    }

    sb.append('\n')
    sb.append(generateRootApplyConfig(info))

    sb.toString
  }

  private def capitalize(s: String): String =
    if (s.isEmpty) s else s.charAt(0).toUpper.toString + s.substring(1)

  private def generateNestedApplyConfig(
    cc: CaseClassDef,
    caseClassNames: Set[String],
    enumNames: Set[String],
    objectName: String
  ): String = {
    val className = cc.name + "ApplyConfig"
    val pad = "  "
    val sb = new StringBuilder

    // Transform each field
    val transformedFields: List[(String, FieldType)] = cc.fields.map { f =>
      (f.name, transformFieldType(f.fieldType, caseClassNames, enumNames, objectName))
    }

    // Case class definition
    sb.append(s"${pad}case class $className(\n")
    for (((name, ft), idx) <- transformedFields.zipWithIndex) {
      val comma = if (idx < transformedFields.size - 1) "," else ""
      sb.append(s"$pad  $name: ${ft.render} = None$comma\n")
    }
    sb.append(s"$pad):\n")

    // with* methods
    for ((name, ft) <- transformedFields) {
      val paramType = unwrapOption(ft)
      sb.append(s"$pad  def with${capitalize(name)}(v: ${paramType.render}): $className = copy($name = Some(v))\n")
    }

    sb.append('\n')

    // Companion object with OWrites
    sb.append(s"${pad}object $className:\n")
    sb.append(generateOWrites(className, transformedFields, pad + "  "))

    sb.toString
  }

  private def generateOWrites(
    className: String,
    fields: List[(String, FieldType)],
    pad: String
  ): String = {
    val sb = new StringBuilder
    val maxGroupSize = 22

    if (fields.size <= maxGroupSize) {
      sb.append(s"${pad}given OWrites[$className] = (\n")
      for (((name, ft), idx) <- fields.zipWithIndex) {
        val writeType = unwrapOption(ft).render
        val sep = if (idx < fields.size - 1) " and" else ""
        sb.append(s"$pad  ${jsPathField(name)}.writeNullable[$writeType]$sep\n")
      }
      val extractors = fields.map { case (name, _) => s"s.$name" }.mkString(", ")
      sb.append(s"$pad)(s => ($extractors))\n")
    } else {
      val groups = fields.grouped(maxGroupSize).toList

      sb.append(s"${pad}given OWrites[$className] =\n")

      for ((group, groupIdx) <- groups.zipWithIndex) {
        val partName = s"part${groupIdx + 1}"
        sb.append(s"$pad  val $partName = (\n")
        for (((name, ft), idx) <- group.zipWithIndex) {
          val writeType = unwrapOption(ft).render
          val sep = if (idx < group.size - 1) " and" else ""
          sb.append(s"$pad    ${jsPathField(name)}.writeNullable[$writeType]$sep\n")
        }
        sb.append(s"$pad  ).tupled\n\n")
      }

      sb.append(s"$pad  OWrites[$className] { s =>\n")
      for ((group, groupIdx) <- groups.zipWithIndex) {
        val partName = s"part${groupIdx + 1}"
        val pName = s"p${groupIdx + 1}"
        val extractors = group.map { case (name, _) => s"s.$name" }.mkString(", ")
        sb.append(s"$pad    val $pName = $partName.writes(($extractors))\n")
      }
      val mergeExpr = (1 to groups.size).map(i => s"p$i").reduce((a, b) => s"$a.deepMerge($b)")
      sb.append(s"$pad    $mergeExpr\n")
      sb.append(s"$pad  }\n")
    }

    sb.toString
  }

  private def generateRootApplyConfig(info: CustomResourceInfo): String = {
    val pad = "  "
    val className = info.kind + "ApplyConfig"
    val apiVersion = s"${info.group}/${info.version}"
    val sb = new StringBuilder

    // Case class
    sb.append(s"${pad}case class $className(\n")
    sb.append(s"$pad  kind: String = ${strLit(info.kind)},\n")
    sb.append(s"$pad  apiVersion: String = ${strLit(apiVersion)},\n")
    sb.append(s"$pad  metadata: Option[ObjectMetaApplyConfig] = None,\n")
    if (info.hasStatus) {
      sb.append(s"$pad  spec: Option[SpecApplyConfig] = None,\n")
      sb.append(s"$pad  status: Option[StatusApplyConfig] = None\n")
    } else {
      sb.append(s"$pad  spec: Option[SpecApplyConfig] = None\n")
    }
    sb.append(s"$pad) extends ApplyConfiguration[${info.objectName}.Resource]:\n")
    sb.append(s"$pad  def name: String = metadata.flatMap(_.name).getOrElse(${DQ}${DQ})\n")
    sb.append(s"$pad  def withMetadata(m: ObjectMetaApplyConfig): $className = copy(metadata = Some(m))\n")
    sb.append(s"$pad  def withSpec(s: SpecApplyConfig): $className = copy(spec = Some(s))\n")
    if (info.hasStatus) {
      sb.append(s"$pad  def withStatus(s: StatusApplyConfig): $className = copy(status = Some(s))\n")
    }
    sb.append(s"$pad  def addLabel(kv: (String, String)): $className =\n")
    sb.append(s"$pad    copy(metadata = Some(metadata.getOrElse(ObjectMetaApplyConfig()).addLabel(kv)))\n")
    sb.append(s"$pad  def addAnnotation(kv: (String, String)): $className =\n")
    sb.append(s"$pad    copy(metadata = Some(metadata.getOrElse(ObjectMetaApplyConfig()).addAnnotation(kv)))\n")

    sb.append('\n')

    // Companion object
    sb.append(s"${pad}object $className:\n")
    sb.append(s"$pad  def apply(name: String): $className =\n")
    sb.append(s"$pad    $className(metadata = Some(ObjectMetaApplyConfig(name = Some(name))))\n")
    sb.append('\n')

    // OWrites for root — kind and apiVersion use write (not writeNullable)
    sb.append(s"$pad  given OWrites[$className] = (\n")
    sb.append(s"$pad    ${jsPathField("kind")}.write[String] and\n")
    sb.append(s"$pad    ${jsPathField("apiVersion")}.write[String] and\n")
    sb.append(s"$pad    ${jsPathField("metadata")}.writeNullable[ObjectMetaApplyConfig] and\n")
    if (info.hasStatus) {
      sb.append(s"$pad    ${jsPathField("spec")}.writeNullable[SpecApplyConfig] and\n")
      sb.append(s"$pad    ${jsPathField("status")}.writeNullable[StatusApplyConfig]\n")
      sb.append(s"$pad  )(a => (a.kind, a.apiVersion, a.metadata, a.spec, a.status))\n")
    } else {
      sb.append(s"$pad    ${jsPathField("spec")}.writeNullable[SpecApplyConfig]\n")
      sb.append(s"$pad  )(a => (a.kind, a.apiVersion, a.metadata, a.spec))\n")
    }

    sb.toString
  }

  // ==================== Entry Point ====================

  def generate(sourceDirectories: Seq[File], outputDir: File): Seq[File] = {
    val scalaFiles = sourceDirectories.flatMap { dir =>
      if (dir.exists()) (dir ** "*.scala").get else Nil
    }

    val results = scalaFiles.flatMap(parseSourceFile)

    results.map { info =>
      val code = generateApplyConfig(info)
      val packagePath = info.packageName.replace('.', '/')
      val outputFile = outputDir / packagePath / (info.objectName + "ApplyConfigs.scala")
      IO.write(outputFile, code)
      outputFile
    }.toSeq
  }
}
