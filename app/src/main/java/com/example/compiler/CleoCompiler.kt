package com.example.compiler

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compilador de scripts CLEO de alto rendimiento para GTA San Andreas (formatos .csa y .csi).
 *
 * Arquitectura de compilación de 2 fases (Two-Pass) precisa y en tiempo real:
 * - Fase 1: Análisis léxico y sintáctico real, soporte para directivas, bloques HEX..END,
 *   expresiones matemáticas de Sanny Builder, resolución de comandos de alto nivel,
 *   construcción de AST y cálculo exacto de offsets de bytecode.
 * - Fase 2: Resolución de etiquetas con offsets relativos negativos estrictos (previene
 *   el bug de recarga de main.scm), emisión de bytecode Little Endian con tipado estricto
 *   de GTA SA (Int8: 0x04, Int16: 0x05, Int32: 0x01, Float: 0x06, LocalVar: 0x03,
 *   GlobalVar: 0x02, String8: 0x09, String16: 0x0F, StringVar: 0x0E).
 * - Protección contra cierres de juego (Crash Prevention):
 *   1. Mapeo fiel de variables globales del juego ($PLAYER_CHAR=8, $PLAYER_ACTOR=12, $PLAYER_GROUP=16).
 *   2. Adición automática del terminador CLEO 0A93: end_custom_thread.
 *   3. Strings variables codificados con la longitud SCM correcta (0E no es
 *      una cadena terminada en NUL).
 * - Medición real de tiempo de compilación (nanosegundos a milisegundos reales, sin simulaciones ni retardos).
 */
object CleoCompiler {

  private sealed class ParsedItem {
    data class Directive(val text: String, val lineNumber: Int) : ParsedItem()
    data class LabelDef(val name: String, val lineNumber: Int) : ParsedItem()
    data class RawBytes(val bytes: ByteArray, val lineNumber: Int) : ParsedItem() {
      override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawBytes
        return bytes.contentEquals(other.bytes) && lineNumber == other.lineNumber
      }
      override fun hashCode(): Int = 31 * bytes.contentHashCode() + lineNumber
    }
    data class Instruction(
      val opcodeInt: Int,
      val opcodeDef: OpcodeDef,
      val params: List<ScriptParam>,
      val lineNumber: Int,
      val rawLine: String
    ) : ParsedItem()
  }

  private sealed class ScriptParam {
    data class LabelRef(val labelName: String) : ScriptParam()
    data class LocalVar(val index: Int) : ScriptParam()
    data class GlobalVar(val offset: Int, val name: String) : ScriptParam()
    data class IntVal(val value: Int) : ScriptParam()
    data class FloatVal(val value: Float) : ScriptParam()
    data class ShortStringVal(val text: String) : ScriptParam()
    data class MediumStringVal(val text: String) : ScriptParam()
    data class VarStringVal(val text: String) : ScriptParam()
  }

  // Mapeo de modelos emblemáticos de GTA SA
  private val gtaModels = mapOf(
    // Vehículos populares
    "LANDSTAL" to 400, "BRAVURA" to 401, "BUFFALO" to 402, "LINERUN" to 403, "PEREN" to 404, "SENTINEL" to 405,
    "DUMPER" to 406, "FIRETRUK" to 407, "TRASH" to 408, "STRETCH" to 409, "MANANA" to 410, "INFERNUS" to 411,
    "VOODOO" to 412, "PONY" to 413, "MULE" to 414, "CHEETAH" to 415, "AMBULAN" to 416, "LEVIATHN" to 417,
    "MOONBEAM" to 418, "ESPERANT" to 419, "TAXI" to 420, "WASHINGTON" to 421, "BOBCAT" to 422, "MRWHOOP" to 423,
    "BFINJECT" to 424, "HUNTER" to 425, "PREMIER" to 426, "ENFORCER" to 427, "SECURICA" to 428, "BANSHEE" to 429,
    "PREDATOR" to 430, "BUS" to 431, "RHINO" to 432, "BARRACKS" to 433, "HOTKNIFE" to 434, "ARTICT1" to 435,
    "PACKER" to 443, "MONSTER" to 444, "TURISMO" to 451, "SPEEDER" to 452, "PCJ600" to 461, "FAGGIO" to 462,
    "FREEWAY" to 463, "SANCHEZ" to 468, "QUAD" to 471, "RUSTLER" to 476, "ZR350" to 477, "COMET" to 480,
    "BMX" to 481, "MAVERICK" to 487, "HOTRING" to 494, "SANDKING" to 495, "HYDRA" to 520, "FCR900" to 521,
    "NRG500" to 522, "COPBIKE" to 523, "TRACTOR" to 531, "COMBINE" to 532, "VORTEX" to 539, "BULLET" to 541,
    "SULTAN" to 560, "ELEGY" to 562, "BANDITO" to 568, "POLICELS" to 596,
    // Armas
    "BRASSKNUCKLE" to 331, "GOLFCLUB" to 333, "NIGHTSTICK" to 334, "KNIFE" to 335, "BASEBALLBAT" to 336,
    "KATANA" to 339, "CHAINSAW" to 341, "GRENADE" to 342, "MOLOTOV" to 344, "COLT45" to 346,
    "SILENCED" to 347, "DESERTEAGLE" to 348, "SHOTGUN" to 349, "SAWNOFF" to 350, "SPAS12" to 351,
    "MICRO_UZI" to 352, "MP5" to 353, "AK47" to 355, "M4" to 356, "COUNTRYRIFLE" to 357, "SNIPER" to 358,
    "ROCKETLAUNCHER" to 359, "HEATSEEKING" to 360, "FLAMETHROWER" to 361, "MINIGUN" to 362, "PARACHUTE" to 371,
    // Personajes
    "CJ" to 0, "TRUTH" to 1, "MACER" to 2, "SMOKE" to 269, "SWEET" to 270, "KENDL" to 271, "RYDER" to 272
  )

  /**
   * Compila el código fuente en texto a bytecode ejecutable de CLEO.
   * Ejecuta en tiempo real sin pausas artificiales y mide la duración real en milisegundos.
   */
  fun compile(sourceCode: String): CompilationResult {
    val startTimeNanos = System.nanoTime()

    val lines = sourceCode.lines()
    if (lines.all { it.isBlank() || it.trim().startsWith("//") || it.trim().startsWith(";") || it.trim().startsWith("#") }) {
      return CompilationResult.Failure(
        CompilationError(
          line = 1,
          rawLine = sourceCode.take(40),
          type = CompilerErrorType.EMPTY_SOURCE,
          message = "El código fuente está vacío. Agrega al menos una instrucción CLEO.",
          suggestion = "Ejemplo: 0001: wait 0 ms"
        )
      )
    }

    val parsedItems = mutableListOf<ParsedItem>()
    val globalVarsAlloc = mutableMapOf<String, Int>()
    var nextGlobalOffset = 1024 // Offsets de variables globales dinámicas comienzan en 1024

    fun getGlobalOffset(varName: String): Int {
      val upper = varName.uppercase().trim().removePrefix("$")
      when (upper) {
        "PLAYER_CHAR" -> return 8 // Variable 2 (Player info struct index)
        "PLAYER_ACTOR", "PLAYER_PED", "PLAYER" -> return 12 // Variable 3 (CPed handle / pointer)
        "PLAYER_GROUP" -> return 16 // Variable 4
        "ONMISSION" -> return 128 // Variable 32
      }
      val numeric = upper.toIntOrNull()
      if (numeric != null) {
        return numeric * 4
      }
      return globalVarsAlloc.getOrPut(upper) {
        val assigned = nextGlobalOffset
        nextGlobalOffset += 4
        assigned
      }
    }

    // =========================================================================
    // FASE 0: Parseo léxico y sintáctico línea por línea
    // =========================================================================
    var insideHexBlock = false
    val hexBlockBytes = mutableListOf<Byte>()

    lines.forEachIndexed { index, rawLine ->
      val lineNumber = index + 1
      var clean = rawLine.trim()

      if (clean.isEmpty() || clean.startsWith("//") || clean.startsWith(";") || clean.startsWith("#")) {
        return@forEachIndexed
      }

      // Remover comentarios inline (// o ; o #)
      clean = clean.split("//", ";").first().trim()
      if (clean.isEmpty()) {
        return@forEachIndexed
      }

      // Bloques HEX..END
      val lowerClean = clean.lowercase()
      if (lowerClean == "hex") {
        insideHexBlock = true
        hexBlockBytes.clear()
        return@forEachIndexed
      }
      if (lowerClean == "end" && insideHexBlock) {
        insideHexBlock = false
        if (hexBlockBytes.isNotEmpty()) {
          parsedItems.add(ParsedItem.RawBytes(hexBlockBytes.toByteArray(), lineNumber))
          hexBlockBytes.clear()
        }
        return@forEachIndexed
      }
      if (insideHexBlock) {
        val tokens = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (token in tokens) {
          if (token.lowercase() == "end") {
            insideHexBlock = false
            break
          }
          val b = token.toIntOrNull(16)
          if (b != null) {
            hexBlockBytes.add(b.toByte())
          }
        }
        if (!insideHexBlock && hexBlockBytes.isNotEmpty()) {
          parsedItems.add(ParsedItem.RawBytes(hexBlockBytes.toByteArray(), lineNumber))
          hexBlockBytes.clear()
        }
        return@forEachIndexed
      }

      // Soporte HEX inline: hex 01 02 03 end
      if (lowerClean.startsWith("hex ") && lowerClean.endsWith(" end")) {
        val content = clean.substring(4, clean.length - 4).trim()
        val tokens = content.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val bytes = mutableListOf<Byte>()
        for (t in tokens) {
          val b = t.toIntOrNull(16)
          if (b != null) bytes.add(b.toByte())
        }
        if (bytes.isNotEmpty()) {
          parsedItems.add(ParsedItem.RawBytes(bytes.toByteArray(), lineNumber))
        }
        return@forEachIndexed
      }

      // Directivas del compilador Sanny Builder / CLEO (ej. {$CLEO .csa}, {$NOSAVE})
      if (clean.startsWith("{$")) {
        parsedItems.add(ParsedItem.Directive(clean, lineNumber))
        return@forEachIndexed
      }

      // Comprobar si hay una etiqueta al inicio (ej. :LABEL o @LABEL o LABEL: o .LABEL)
      val leadingLabelMatch = Regex("^([:@.][A-Za-z0-9_]+|[A-Za-z0-9_]+:)(.*)$").find(clean)
      if (leadingLabelMatch != null) {
        val candidate = leadingLabelMatch.groupValues[1]
        val restAfter = leadingLabelMatch.groupValues[2].trim()

        val labelName = candidate.removePrefix(":").removePrefix("@").removePrefix(".").removeSuffix(":").uppercase()

        // Asegurarse de que no sea un opcode con dos puntos (ej. 0001:)
        if (!candidate.matches(Regex("^[0-9A-Fa-f]{4}:?$"))) {
          parsedItems.add(ParsedItem.LabelDef(labelName, lineNumber))
          if (restAfter.isEmpty() || restAfter.startsWith("//") || restAfter.startsWith(";") || restAfter.startsWith("#")) {
            return@forEachIndexed
          }
          clean = restAfter
        }
      }

      // Verificar si hay prefijo de condición NOT (ej. NOT 00DF: is_char_in_any_car)
      var isNegated = false
      if (clean.startsWith("not ", ignoreCase = true)) {
        isNegated = true
        clean = clean.substring(4).trim()
      }

      // Extraer el opcode inicial o deducir sintaxis inteligente de Sanny Builder
      var opcodeHex: String? = null
      var argumentsRest = ""

      val parts = clean.split(Regex("\\s+"), limit = 2)
      val firstToken = parts.getOrNull(0)?.trim() ?: ""

      val opcodeMatch = Regex("^([0-9A-Fa-f]{4}):?$").find(firstToken)
      if (opcodeMatch != null) {
        // Formato estándar con código hexadecimal: 0001: wait 0 ms
        opcodeHex = opcodeMatch.groupValues[1].uppercase()
        argumentsRest = if (parts.size > 1) parts[1].trim() else ""
      } else {
        // Inteligencia estilo Sanny Builder:
        // 1. Expresión matemática / asignación / comparación (ej. $VAR = 10, 0@ += 1, 0@ > 5)
        val sannyMathMatch = Regex("^(\\$?[A-Za-z0-9_@]+)\\s*(==|>=|<=|!=|<>|\\+=|-=|\\*=|/=|=(?!=)|>|<)\\s*(.+)$").find(clean)
        if (sannyMathMatch != null) {
          val left = sannyMathMatch.groupValues[1].trim()
          val op = sannyMathMatch.groupValues[2].trim()
          val right = sannyMathMatch.groupValues[3].trim()
          val resolvedHex = resolveSannyMathOpcode(left, op, right)
          if (resolvedHex != null) {
            opcodeHex = resolvedHex
            argumentsRest = "$left $right"
          }
        }

        // 2. Comandos directos y palabras clave (ej. wait 0 ms, jump @LOOP, end_thread, create_player, etc.)
        if (opcodeHex == null) {
          val resolvedCmd = resolveSannyKeywordOrCommand(clean)
          if (resolvedCmd != null) {
            opcodeHex = resolvedCmd.first
            argumentsRest = resolvedCmd.second
          }
        }
      }

      if (opcodeHex == null) {
        return CompilationResult.Failure(
          CompilationError(
            line = lineNumber,
            rawLine = rawLine,
            type = CompilerErrorType.INVALID_OPCODE_FORMAT,
            message = "Instrucción no reconocida o formato de opcode inválido: '$firstToken'. Puedes usar formato hexadecimal (ej: '0001: wait 0 ms') o sintaxis Sanny Builder (ej: 'wait 0 ms', '0@ = 10', '\$VAR += 1', 'jump @LABEL', 'end_thread').",
            suggestion = "Revisa la instrucción. Opcodes comunes: 0001 (wait), 0A93 (end_custom_thread), 03A4 (name_thread), o expresiones como 0@ = 1."
          )
        )
      }

      var effectiveOpcodeHex = opcodeHex!!
      var opcodeInt = effectiveOpcodeHex.toInt(16)

      // 004E termina scripts del main.scm y provoca cierres al usarlo en CLEO.
      // Se acepta como alias heredado para que los proyectos existentes no
      // generen un archivo peligroso, pero siempre se emite el terminador CLEO.
      if (opcodeInt == 0x004E) {
        opcodeInt = 0x0A93
        effectiveOpcodeHex = "0A93"
      }

      // Si empieza con 8 (ej. 80DF), en SCM de GTA SA significa condición negada
      if (opcodeInt >= 0x8000) {
        isNegated = true
      }

      val opcodeDef = CleoOpcodeDatabase.findByHex(effectiveOpcodeHex)
      if (opcodeDef == null) {
        return CompilationResult.Failure(
          CompilationError(
            line = lineNumber,
            rawLine = rawLine,
            type = CompilerErrorType.UNKNOWN_OPCODE,
            message = "Opcode no reconocido: '$opcodeHex'. Este opcode no existe en el catálogo de instrucciones de GTA San Andreas ni en CLEO Android.",
            suggestion = "Verifica la sintaxis del opcode. Opcodes comunes: 0001 (wait), 0A93 (end_custom_thread), 03A4 (name_thread)."
          )
        )
      }

      if (isNegated) {
        opcodeInt = opcodeInt or 0x8000
      }

      val parseResult = parseInstructionArguments(
        opcodeDef = opcodeDef,
        opcodeInt = opcodeInt,
        argumentsRest = argumentsRest,
        lineNumber = lineNumber,
        rawLine = rawLine,
        getGlobalOffset = ::getGlobalOffset
      )

      when (parseResult) {
        is ParseResult.Error -> return CompilationResult.Failure(parseResult.error)
        is ParseResult.Success -> {
          parsedItems.add(
            ParsedItem.Instruction(
              opcodeInt = opcodeInt,
              opcodeDef = opcodeDef,
              params = parseResult.params,
              lineNumber = lineNumber,
              rawLine = rawLine
            )
          )
        }
      }
    }

    // =========================================================================
    // FASE 1: Cálculo exacto de offsets y registro de etiquetas
    // =========================================================================
    val labelOffsetMap = mutableMapOf<String, Int>()
    var currentBytecodeOffset = 0

    parsedItems.forEach { item ->
      when (item) {
        is ParsedItem.Directive -> {}
        is ParsedItem.RawBytes -> {
          currentBytecodeOffset += item.bytes.size
        }
        is ParsedItem.LabelDef -> {
          labelOffsetMap[item.name] = currentBytecodeOffset
        }
        is ParsedItem.Instruction -> {
          var instructionSize = 2
          item.params.forEach { param ->
            instructionSize += getParamByteSize(param)
          }
          currentBytecodeOffset += instructionSize
        }
      }
    }

    // =========================================================================
    // FASE 2: Emisión de Bytecode SCM en Little Endian
    // =========================================================================
    val outputStream = ByteArrayOutputStream()
    var opcodesCount = 0
    var lastInstructionOpcode: Int? = null

    for (item in parsedItems) {
      when (item) {
        is ParsedItem.RawBytes -> {
          outputStream.write(item.bytes)
        }
        is ParsedItem.Instruction -> {
          lastInstructionOpcode = item.opcodeInt and 0x7FFF

          // 1. Escribir opcode (2 bytes little-endian)
          outputStream.write(item.opcodeInt and 0xFF)
          outputStream.write((item.opcodeInt shr 8) and 0xFF)

          // 2. Escribir parámetros
          for (param in item.params) {
            when (param) {
              is ScriptParam.LabelRef -> {
                val targetOffset = labelOffsetMap[param.labelName.uppercase()]
                if (targetOffset == null) {
                  return CompilationResult.Failure(
                    CompilationError(
                      line = item.lineNumber,
                      rawLine = item.rawLine,
                      type = CompilerErrorType.SYNTAX_ERROR,
                      message = "La etiqueta '@${param.labelName}' no está definida en el script.",
                      suggestion = "Asegúrate de definir la etiqueta usando :${param.labelName} antes o después de la llamada."
                    )
                  )
                }
                // En CLEO GTA SA, los saltos relativos son negativos (-targetOffset)
                val relativeOffset = -targetOffset
                outputStream.write(0x01) // Tipo Int32
                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(relativeOffset)
                outputStream.write(buf.array())
              }

              is ScriptParam.LocalVar -> {
                outputStream.write(0x03) // Tipo LOCAL_VAR
                val offsetBytes = (param.index * 4).toShort()
                val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(offsetBytes)
                outputStream.write(buf.array())
              }

              is ScriptParam.GlobalVar -> {
                outputStream.write(0x02) // Tipo GLOBAL_VAR
                val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(param.offset.toShort())
                outputStream.write(buf.array())
              }

              is ScriptParam.IntVal -> {
                val v = param.value
                if (v in -128..127) {
                  outputStream.write(0x04) // Tipo INT8
                  outputStream.write(v and 0xFF)
                } else if (v in -32768..32767) {
                  outputStream.write(0x05) // Tipo INT16
                  val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort())
                  outputStream.write(buf.array())
                } else {
                  outputStream.write(0x01) // Tipo INT32
                  val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
                  outputStream.write(buf.array())
                }
              }

              is ScriptParam.FloatVal -> {
                outputStream.write(0x06) // Tipo FLOAT (IEEE 754 de 4 bytes)
                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(param.value)
                outputStream.write(buf.array())
              }

              is ScriptParam.ShortStringVal -> {
                outputStream.write(0x09) // Tipo STRING_SHORT (8 bytes nulos-rellenados)
                val nameBytes = ByteArray(8)
                val ascii = param.text.take(8).toByteArray(Charsets.ISO_8859_1)
                System.arraycopy(ascii, 0, nameBytes, 0, ascii.size)
                outputStream.write(nameBytes)
              }

              is ScriptParam.MediumStringVal -> {
                outputStream.write(0x0F) // Tipo STRING_16 (16 bytes nulos-rellenados para GTA SA)
                val nameBytes = ByteArray(16)
                val ascii = param.text.take(16).toByteArray(Charsets.ISO_8859_1)
                System.arraycopy(ascii, 0, nameBytes, 0, ascii.size)
                outputStream.write(nameBytes)
              }

              is ScriptParam.VarStringVal -> {
                // SCM 0x0E es una cadena inmediata de longitud variable:
                // tipo + longitud + bytes. No lleva terminador NUL; agregarlo
                // desplaza el siguiente parámetro y corrompe la instrucción.
                outputStream.write(0x0E)
                val textBytes = param.text.toByteArray(Charsets.ISO_8859_1)
                outputStream.write(textBytes.size and 0xFF)
                outputStream.write(textBytes)
              }
            }
          }

          opcodesCount++
        }
        else -> {}
      }
    }

    // =========================================================================
    // PROTECCIÓN CONTRA CRASH: Terminador seguro de hilo
    // =========================================================================
    if (lastInstructionOpcode != null &&
      lastInstructionOpcode != 0x0002 &&
      lastInstructionOpcode != 0x0A93 &&
      lastInstructionOpcode != 0x0051
    ) {
      // 004E es TERMINATE_THIS_SCRIPT y solo es válido para main.scm.
      // Los scripts CLEO deben finalizar con 0A93.
      outputStream.write(0x93)
      outputStream.write(0x0A)
      opcodesCount++
    }

    val compiledBytes = outputStream.toByteArray()
    val hexDump = compiledBytes.joinToString(" ") { "%02X".format(it) }
    val detectedScriptName = inferScriptName(sourceCode, "csa")

    val elapsedDurationNanos = System.nanoTime() - startTimeNanos
    val elapsedDurationMs = (elapsedDurationNanos / 1_000_000.0).toLong().coerceAtLeast(1L)

    return CompilationResult.Success(
      bytecode = compiledBytes,
      opcodesCompiled = opcodesCount,
      totalLines = lines.size,
      hexDump = hexDump,
      scriptName = detectedScriptName,
      compilationTimeMs = elapsedDurationMs
    )
  }

  fun inferScriptName(sourceCode: String, targetExtension: String = "csa"): String {
    val cleanExt = targetExtension.trim().removePrefix(".").lowercase().ifEmpty { "csa" }
    val lines = sourceCode.lines()

    // 1. Directivas explícitas {$NAME mi_script} o comentarios estructurados // name: mi_script
    for (line in lines) {
      val t = line.trim()
      val directiveMatch = Regex("\\{\\$(?:NAME|SCRIPT_NAME|FILE_NAME)\\s+([A-Za-z0-9_\\-]+)\\}", RegexOption.IGNORE_CASE).find(t)
      if (directiveMatch != null) {
        val name = sanitizeFileName(directiveMatch.groupValues[1])
        if (name.isNotEmpty()) return "$name.$cleanExt"
      }

      val commentNameMatch = Regex("^(?://|;|#)\\s*(?:name|script|mod|title)\\s*[:=]\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(t)
      if (commentNameMatch != null) {
        val name = sanitizeFileName(commentNameMatch.groupValues[1])
        if (name.isNotEmpty()) return "$name.$cleanExt"
      }
    }

    // 2. Opcode 03A4: name_thread 'NOMBRE'
    for (line in lines) {
      val t = line.trim()
      val threadMatch = Regex("(?:03A4\\s*:\\s*name_thread|name_thread)\\s*['\"]([A-Za-z0-9_\\-]+)['\"]", RegexOption.IGNORE_CASE).find(t)
      if (threadMatch != null) {
        val raw = threadMatch.groupValues[1].trim()
        val lower = raw.lowercase()
        if (lower !in listOf("main", "thread", "script", "noname") && raw.isNotEmpty()) {
          val sanitized = sanitizeFileName(raw)
          if (sanitized.isNotEmpty()) return "$sanitized.$cleanExt"
        }
      }
    }

    // 3. Primer comentario relevante corto que actúe como título
    for (line in lines) {
      val t = line.trim()
      if (t.startsWith("//") || t.startsWith(";") || t.startsWith("#")) {
        val cleaned = t.replace(Regex("^[//;\\s*#]+"), "").trim()
        val words = cleaned.split(Regex("[\\s\\-_]+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.size <= 4 && words.all { it.matches(Regex("^[A-Za-z0-9]+$")) }) {
          val candidate = words.joinToString("_").lowercase()
          if (candidate.length in 3..24 && candidate !in listOf("cleo_script", "script", "code", "untitled")) {
            val sanitized = sanitizeFileName(candidate)
            if (sanitized.isNotEmpty()) return "$sanitized.$cleanExt"
          }
        }
      }
    }

    // 4. Inferencia semántica
    val codeLower = sourceCode.lowercase()

    if (codeLower.contains("0056") || codeLower.contains("make_actor_say") || codeLower.contains("say_phrase") ||
      codeLower.contains("play_sound") || codeLower.contains("018c") || codeLower.contains("play_music") ||
      codeLower.contains("audio_stream") || codeLower.contains("voice")
    ) {
      return if (cleanExt == "csi") "voice_dialogue.csi" else "voice_mod.csa"
    }

    if (codeLower.contains("0de0") || codeLower.contains("0de1") || codeLower.contains("0de2") ||
      codeLower.contains("0de3") || codeLower.contains("0de4") || codeLower.contains("0de5") ||
      codeLower.contains("touch") || codeLower.contains("gesture") || codeLower.contains("swipe")
    ) {
      return if (cleanExt == "csi") "touch_actions.csi" else "touch_controls.csa"
    }

    if (codeLower.contains("0dd8") || codeLower.contains("0dd9") || codeLower.contains("0dda") ||
      codeLower.contains("0ddb") || codeLower.contains("0ddc") || codeLower.contains("0ddd") ||
      codeLower.contains("cleo_menu") || codeLower.contains("081e") || codeLower.contains("create_menu")
    ) {
      return if (cleanExt == "csi") "cleo_menu.csi" else "custom_menu.csa"
    }

    if (codeLower.contains("0109") || codeLower.contains("010a") || codeLower.contains("010b") ||
      codeLower.contains("010e") || codeLower.contains("player_add_money") || codeLower.contains("player_set_money") ||
      codeLower.contains("add_money") || codeLower.contains("cash") || codeLower.contains("032b")
    ) {
      return if (cleanExt == "csi") "money_menu.csi" else "money_mod.csa"
    }

    if (codeLower.contains("00a5") || codeLower.contains("create_car") || codeLower.contains("infernus") ||
      codeLower.contains("turismo") || codeLower.contains("bullet") || codeLower.contains("car_spawner")
    ) {
      return if (cleanExt == "csi") "car_spawner.csi" else "auto_vehicle.csa"
    }

    if (codeLower.contains("give_actor_weapon") || codeLower.contains("01b2") || codeLower.contains("05e2") ||
      codeLower.contains("minigun") || codeLower.contains("rocket")
    ) {
      return if (cleanExt == "csi") "weapons_menu.csi" else "weapons_mod.csa"
    }

    if (codeLower.contains("set_actor_coordinates") || codeLower.contains("00a1") || codeLower.contains("teleport")) {
      return if (cleanExt == "csi") "teleport_menu.csi" else "teleport.csa"
    }

    if (codeLower.contains("02ab") || codeLower.contains("set_actor_immunities") || codeLower.contains("godmode")) {
      return "godmode.$cleanExt"
    }

    if (codeLower.contains("0812") || codeLower.contains("apply_animation") || codeLower.contains("walk_style")) {
      return if (cleanExt == "csi") "anim_trigger.csi" else "anim_mod.csa"
    }

    return if (cleanExt == "csi") "menu_script.csi" else "cleo_mod.csa"
  }

  private fun sanitizeFileName(input: String): String {
    return input.trim()
      .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
      .trim('_')
      .lowercase()
  }

  private fun getParamByteSize(param: ScriptParam): Int = when (param) {
    is ScriptParam.LabelRef -> 1 + 4 // 0x01 + 4 bytes INT32
    is ScriptParam.LocalVar -> 1 + 2 // 0x03 + 2 bytes offset
    is ScriptParam.GlobalVar -> 1 + 2 // 0x02 + 2 bytes offset
    is ScriptParam.IntVal -> {
      val v = param.value
      if (v in -128..127) 1 + 1 // 0x04 + 1 byte
      else if (v in -32768..32767) 1 + 2 // 0x05 + 2 bytes
      else 1 + 4 // 0x01 + 4 bytes
    }
    is ScriptParam.FloatVal -> 1 + 4 // 0x06 + 4 bytes Float
    is ScriptParam.ShortStringVal -> 1 + 8 // 0x09 + 8 bytes
    is ScriptParam.MediumStringVal -> 1 + 16 // 0x0F + 16 bytes
    is ScriptParam.VarStringVal -> 1 + 1 + param.text.toByteArray(Charsets.ISO_8859_1).size // 0x0E + len + bytes
  }

  private sealed class ParseResult {
    data class Success(val params: List<ScriptParam>) : ParseResult()
    data class Error(val error: CompilationError) : ParseResult()
  }

  private fun parseInstructionArguments(
    opcodeDef: OpcodeDef,
    opcodeInt: Int,
    argumentsRest: String,
    lineNumber: Int,
    rawLine: String,
    getGlobalOffset: (String) -> Int
  ): ParseResult {
    val cleanOpcode = opcodeInt and 0x7FFF

    // Opcodes sin parámetros conocidos
    when (cleanOpcode) {
      0x0000, 0x004E, 0x0051, 0x00BE, 0x00BF, 0x038B, 0x015F, 0x016A, 0x0249, 0x06FD, 0x0DD8, 0x0DDD, 0x0395, 0x0A93 -> {
        return ParseResult.Success(emptyList())
      }

      // 0001: wait X ms
      0x0001 -> {
        val tokens = argumentsRest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var timeParam: ScriptParam? = null
        for (token in tokens) {
          if (token.equals("wait", true) || token.equals("ms", true) || token.equals("sec", true)) continue
          val num = token.toIntOrNull()
          if (num != null) {
            timeParam = ScriptParam.IntVal(num)
            break
          }
          if (token.startsWith("$")) {
            timeParam = ScriptParam.GlobalVar(getGlobalOffset(token.removePrefix("$")), token)
            break
          }
          val localMatch = Regex("^(\\d+)@").find(token)
          if (localMatch != null) {
            timeParam = ScriptParam.LocalVar(localMatch.groupValues[1].toInt())
            break
          }
        }

        if (timeParam == null) {
          return ParseResult.Error(
            CompilationError(
              line = lineNumber,
              rawLine = rawLine,
              type = CompilerErrorType.INVALID_PARAMETERS,
              message = "El opcode 0001: (wait) requiere el tiempo en milisegundos como número entero.",
              suggestion = "Ejemplo: 0001: wait 0 ms"
            )
          )
        }
        return ParseResult.Success(listOf(timeParam))
      }

      // 03A4: name_thread 'MYMOD'
      0x03A4 -> {
        val stringMatch = Regex("['\"]([^'\"]+)['\"]").find(argumentsRest)
        val name = stringMatch?.groupValues?.get(1)
          ?: argumentsRest.substringAfter("name_thread").trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")

        if (name.isBlank()) {
          return ParseResult.Error(
            CompilationError(
              line = lineNumber,
              rawLine = rawLine,
              type = CompilerErrorType.INVALID_PARAMETERS,
              message = "El opcode 03A4: (name_thread) requiere el nombre del hilo entre comillas.",
              suggestion = "Ejemplo: 03A4: name_thread 'MYMOD'"
            )
          )
        }
        return ParseResult.Success(listOf(ScriptParam.ShortStringVal(name.take(7))))
      }

      // 0ACA: show_text_box "Texto"
      0x0ACA -> {
        val stringMatch = Regex("['\"]([^'\"]+)['\"]").find(argumentsRest)
        val text = stringMatch?.groupValues?.get(1) ?: argumentsRest.substringAfter("show_text_box").trim()
        if (text.isBlank()) {
          return ParseResult.Error(
            CompilationError(
              line = lineNumber,
              rawLine = rawLine,
              type = CompilerErrorType.INVALID_PARAMETERS,
              message = "El opcode 0ACA: (show_text_box) requiere el texto entre comillas.",
              suggestion = "Ejemplo: 0ACA: show_text_box \"Hola mundo\""
            )
          )
        }
        return ParseResult.Success(listOf(ScriptParam.VarStringVal(text)))
      }
    }

    // Tokenizador inteligente para opcodes estándar
    val quotedStrings = mutableListOf<String>()
    var processedArgs = argumentsRest

    // Extraer strings con comillas simples y dobles
    Regex("(['\"])(.*?)\\1").findAll(argumentsRest).forEachIndexed { i, match ->
      val fullMatch = match.value
      val content = match.groupValues[2]
      val placeholder = " __STR_${i}__ "
      quotedStrings.add(content)
      processedArgs = processedArgs.replace(fullMatch, placeholder)
    }

    // Detectar si la instrucción fue escrita como asignación: DEST = CMD ARGS...
    var assignmentDestToken: String? = null
    if (processedArgs.contains("=") &&
      !processedArgs.contains("==") &&
      !processedArgs.contains("<=") &&
      !processedArgs.contains(">=") &&
      !processedArgs.contains("!=") &&
      !processedArgs.contains("<>") &&
      !processedArgs.contains("+=") &&
      !processedArgs.contains("-=") &&
      !processedArgs.contains("*=") &&
      !processedArgs.contains("/=")
    ) {
      val leftSide = processedArgs.substringBefore("=").trim()
      val rightSide = processedArgs.substringAfter("=").trim()
      if (leftSide.startsWith("$") || leftSide.matches(Regex("^\\d+@[vs]?$", RegexOption.IGNORE_CASE))) {
        assignmentDestToken = leftSide
        processedArgs = rightSide
      }
    }

    val rawTokens = processedArgs.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    val params = mutableListOf<ScriptParam>()

    val noiseWords = setOf(
      "ms", "sec", "seconds", "to", "at", "from", "with", "in", "is", "actor", "char",
      "car", "vehicle", "object", "player", "model", "position", "position_to", "immunities",
      "health", "armour", "armor", "money", "add_money", "weapon", "ammo", "weather",
      "fade", "time", "set", "get", "store", "and", "or", "not", "jump", "jump_if_false",
      "end_thread", "create_thread", "gosub", "return", "=", "+=", "-=", "*=", "/=", "==", ">=", "<=", "<>", "!=",
      "phrase", "can_move", "abs", "driving", "control", "key_pressed", "as_only_one_available_for_gangwars",
      "as", "only", "one", "available", "for", "gangwars", "zone", "gang", "can", "move"
    )

    val embeddedDef = CleoOpcodeDatabase.getEmbedded(cleanOpcode)
    val commandWords = mutableSetOf<String>()
    fun addCommandNameWords(name: String) {
      val lower = name.lowercase()
      commandWords.add(lower)
      commandWords.add(lower.replace("_", ""))
      commandWords.addAll(lower.split("_").filter { it.isNotEmpty() })
    }
    addCommandNameWords(opcodeDef.commandName)
    embeddedDef?.let { addCommandNameWords(it.commandName) }

    fun addExampleWords(example: String) {
      Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(example).forEach { m ->
        val w = m.value.lowercase()
        if (w !in gtaModels && !w.startsWith("str_")) {
          commandWords.add(w)
          commandWords.addAll(w.split("_").filter { it.isNotEmpty() })
        }
      }
    }
    addExampleWords(opcodeDef.example)
    embeddedDef?.let { addExampleWords(it.example) }

    // Si el primer token tras el opcode es un identificador alfabético (ej. 01B6: set_weather 1),
    // se trata del mnemónico o nombre de comando del script.
    val firstToken = rawTokens.firstOrNull()?.trim()
    if (firstToken != null &&
      Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(firstToken) &&
      !firstToken.equals("true", ignoreCase = true) &&
      !firstToken.equals("false", ignoreCase = true) &&
      !gtaModels.containsKey(firstToken.uppercase())
    ) {
      val ftLower = firstToken.lowercase()
      commandWords.add(ftLower)
      commandWords.add(ftLower.replace("_", ""))
      commandWords.addAll(ftLower.split("_").filter { it.isNotEmpty() })
    }

    for (token in rawTokens) {
      val trimmed = token.trim()
      if (trimmed.isEmpty()) continue

      // Si es un placeholder de cadena
      val strMatch = Regex("^__STR_(\\d+)__$").find(trimmed)
      if (strMatch != null) {
        val index = strMatch.groupValues[1].toInt()
        val text = quotedStrings.getOrNull(index) ?: ""
        if (text.length <= 7) {
          params.add(ScriptParam.ShortStringVal(text))
        } else if (text.length <= 15) {
          params.add(ScriptParam.MediumStringVal(text))
        } else {
          params.add(ScriptParam.VarStringVal(text))
        }
        continue
      }

      val lower = trimmed.lowercase()

      // Si es palabra de comando o palabra de adorno de Sanny Builder, ignorar
      if (noiseWords.contains(lower) || commandWords.contains(lower) || lower == opcodeDef.commandName.lowercase()) {
        continue
      }

      // 1. Etiqueta @LABEL o :LABEL
      if (trimmed.startsWith("@") || trimmed.startsWith(":")) {
        params.add(ScriptParam.LabelRef(trimmed.removePrefix("@").removePrefix(":")))
        continue
      }

      // 2. Variable local N@
      val localMatch = Regex("^(\\d+)@(v|s)?$", RegexOption.IGNORE_CASE).find(trimmed)
      if (localMatch != null) {
        val idx = localMatch.groupValues[1].toInt()
        params.add(ScriptParam.LocalVar(idx))
        continue
      }

      // 3. Variable global $VAR
      if (trimmed.startsWith("$")) {
        val varName = trimmed.removePrefix("$")
        val offset = getGlobalOffset(varName)
        params.add(ScriptParam.GlobalVar(offset, varName))
        continue
      }

      // 4. Float (ej. 0.0, -1666.0, 13.5f)
      if (trimmed.contains(".") && trimmed.removeSuffix("f").removeSuffix("F").toFloatOrNull() != null) {
        val floatVal = trimmed.removeSuffix("f").removeSuffix("F").toFloat()
        params.add(ScriptParam.FloatVal(floatVal))
        continue
      }

      // 5. Modelo con hashtag (ej. #INFERNUS, #411)
      if (trimmed.startsWith("#")) {
        val modelKey = trimmed.removePrefix("#").uppercase()
        val modelId = gtaModels[modelKey] ?: modelKey.toIntOrNull() ?: 0
        params.add(ScriptParam.IntVal(modelId))
        continue
      }

      // 6. Entero hexadecimal (ej. 0x1000)
      if (trimmed.startsWith("0x", ignoreCase = true) || trimmed.startsWith("-0x", ignoreCase = true)) {
        val isNegative = trimmed.startsWith("-")
        val cleanHex = trimmed.removePrefix("-").removePrefix("0x").removePrefix("0X")
        val intVal = cleanHex.toIntOrNull(16)
        if (intVal != null) {
          params.add(ScriptParam.IntVal(if (isNegative) -intVal else intVal))
          continue
        }
      }

      // 7. Entero decimal estándar (ej. 250, -1, 1000)
      val intVal = trimmed.toIntOrNull()
      if (intVal != null) {
        params.add(ScriptParam.IntVal(intVal))
        continue
      }

      // 8. Booleano
      if (trimmed.equals("true", ignoreCase = true)) {
        params.add(ScriptParam.IntVal(1))
        continue
      }
      if (trimmed.equals("false", ignoreCase = true)) {
        params.add(ScriptParam.IntVal(0))
        continue
      }

      // 9. Modelo reconocido directamente por nombre (ej. INFERNUS, HYDRA, AK47)
      val knownModelId = gtaModels[trimmed.uppercase()]
      if (knownModelId != null) {
        params.add(ScriptParam.IntVal(knownModelId))
        continue
      }

      // No convertir silenciosamente un identificador desconocido en string:
      // una palabra mal escrita produce bytecode con el tamaño equivocado y el
      // juego termina leyendo los parámetros siguientes como basura.
      return ParseResult.Error(
        CompilationError(
          line = lineNumber,
          rawLine = rawLine,
          type = CompilerErrorType.INVALID_PARAMETERS,
          message = "Parámetro no reconocido: '$trimmed'.",
          suggestion = "Usa un número, una variable (0@ o \$VAR), una etiqueta (@LABEL), un modelo conocido o una cadena entre comillas."
        )
      )
    }

    // Si hubo una asignación de variable a la izquierda (ej. $CAR = create_car ...),
    // en SCM esa variable de destino se almacena al FINAL de los parámetros de la instrucción
    if (assignmentDestToken != null) {
      if (assignmentDestToken.startsWith("$")) {
        val varName = assignmentDestToken.removePrefix("$")
        params.add(ScriptParam.GlobalVar(getGlobalOffset(varName), varName))
      } else {
        val localIdx = assignmentDestToken.substringBefore("@").toIntOrNull() ?: 0
        params.add(ScriptParam.LocalVar(localIdx))
      }
    }

    val minAllowed = if (assignmentDestToken != null) minOf(opcodeDef.minParams, 1) else opcodeDef.minParams
    val maxAllowed = if (assignmentDestToken != null) maxOf(opcodeDef.maxParams, opcodeDef.maxParams + 1) else opcodeDef.maxParams

    if (params.size < minAllowed || params.size > maxAllowed) {
      val expectedText = if (opcodeDef.minParams == opcodeDef.maxParams) {
        "${opcodeDef.minParams}"
      } else {
        "${opcodeDef.minParams}–${opcodeDef.maxParams}"
      }
      return ParseResult.Error(
        CompilationError(
          line = lineNumber,
          rawLine = rawLine,
          type = CompilerErrorType.INVALID_PARAMETERS,
          message = "El opcode ${opcodeDef.hexString}: (${opcodeDef.commandName}) requiere ${expectedText} parámetro(s), pero se encontraron ${params.size}.",
          suggestion = "Sintaxis recomendada: ${opcodeDef.example}"
        )
      )
    }

    return ParseResult.Success(params)
  }

  private fun isLocalVarToken(token: String): Boolean =
    Regex("^\\d+@[vs]?$", RegexOption.IGNORE_CASE).matches(token.trim())

  private fun isGlobalVarToken(token: String): Boolean =
    token.trim().startsWith("$")

  private fun isFloatToken(token: String): Boolean {
    val clean = token.trim().removeSuffix("f").removeSuffix("F")
    return clean.contains(".") && clean.toFloatOrNull() != null
  }

  private fun resolveSannyMathOpcode(left: String, op: String, right: String): String? {
    val leftIsLocal = isLocalVarToken(left)
    val leftIsGlobal = isGlobalVarToken(left)
    val rightIsVar = isLocalVarToken(right) || isGlobalVarToken(right)
    val rightIsFloat = isFloatToken(right)

    return when (op) {
      "=" -> {
        if (rightIsVar) if (rightIsFloat) "0085" else "0084"
        else if (leftIsLocal) if (rightIsFloat) "0007" else "0006"
        else if (leftIsGlobal) if (rightIsFloat) "0005" else "0004"
        else "0006"
      }
      "+=" -> {
        if (rightIsVar) if (rightIsFloat) "0059" else "0058"
        else if (leftIsLocal) if (rightIsFloat) "000B" else "000A"
        else if (leftIsGlobal) if (rightIsFloat) "0009" else "0008"
        else "000A"
      }
      "-=" -> {
        if (rightIsVar) if (rightIsFloat) "0061" else "0060"
        else if (leftIsLocal) if (rightIsFloat) "000F" else "000E"
        else if (leftIsGlobal) if (rightIsFloat) "000D" else "000C"
        else "000E"
      }
      "*=" -> {
        if (rightIsVar) if (rightIsFloat) "0063" else "0062"
        else if (leftIsLocal) if (rightIsFloat) "0013" else "0012"
        else if (leftIsGlobal) if (rightIsFloat) "0011" else "0010"
        else "0012"
      }
      "/=" -> {
        if (rightIsVar) if (rightIsFloat) "0065" else "0064"
        else if (leftIsLocal) if (rightIsFloat) "0017" else "0016"
        else if (leftIsGlobal) if (rightIsFloat) "0015" else "0014"
        else "0016"
      }
      ">" -> {
        if (rightIsVar) if (rightIsFloat) "0020" else "0018"
        else if (rightIsFloat) "0021" else "0019"
      }
      ">=" -> {
        if (rightIsVar) if (rightIsFloat) "0024" else "001A"
        else if (rightIsFloat) "0025" else "003A"
      }
      "<" -> {
        if (rightIsVar) if (rightIsFloat) "002A" else "0028"
        else if (rightIsFloat) "002B" else "0029"
      }
      "<=" -> {
        if (rightIsVar) if (rightIsFloat) "002E" else "002C"
        else if (rightIsFloat) "002F" else "003B"
      }
      "==" -> "0038"
      "!=", "<>" -> "003C"
      else -> null
    }
  }

  private fun resolveSannyKeywordOrCommand(cleanLine: String): Pair<String, String>? {
    val low = cleanLine.lowercase()

    // Palabras clave directas de Sanny Builder
    if (low.startsWith("wait ") || low == "wait") {
      val args = cleanLine.substring(4).trim().ifEmpty { "0" }
      return Pair("0001", args)
    }
    if (low.startsWith("jump ") || low.startsWith("goto ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("0002", args)
    }
    if (low.startsWith("jf ") || low.startsWith("jump_if_false ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("004D", args)
    }
    if (low == "end_thread" || low == "end_custom_thread" || low == "terminate_this_custom_script" || low == "terminate_this_script") {
      return Pair("0A93", "")
    }
    if (low.startsWith("gosub ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("0050", args)
    }
    if (low == "return") {
      return Pair("0051", "")
    }
    if (low.startsWith("if ") || low == "if") {
      val args = cleanLine.substring(2).trim().ifEmpty { "0" }
      return Pair("00D6", args)
    }
    if (low.startsWith("create_thread ")) {
      val args = cleanLine.substring(13).trim()
      return Pair("00D7", args)
    }
    if (low.startsWith("name_thread ")) {
      val args = cleanLine.substring(11).trim()
      return Pair("03A4", args)
    }
    if (low == "nop") {
      return Pair("0000", "")
    }
    if (low.startsWith("fade ") || low.startsWith("fade_screen ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("016A", args)
    }

    // Comprobación por nombre de comando oficial/personalizado (ej. create_player, show_text_box, name_thread)
    val firstWord = cleanLine.split(Regex("\\s+"), limit = 2)[0]
    val cmdDef = CleoOpcodeDatabase.findByName(firstWord)
    if (cmdDef != null) {
      val rest = cleanLine.substring(firstWord.length).trim()
      return Pair(cmdDef.hexString, rest)
    }

    return null
  }
}
