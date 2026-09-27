package com.example.compiler

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compilador de scripts CLEO de alto rendimiento para GTA San Andreas Android (formatos .csa y .csi).
 *
 * Arquitectura Sanny Builder Completa:
 * - Preprocesador de estructuras de control (if..then..else..end, while..end, repeat..until).
 * - Parser de sintaxis orientada a objetos (OOP: Player.Defined, Actor.Driving, Car.Dead, etc.).
 * - Parser matemático avanzado (0@ += 5.0, 2@ = 2@ + 15.0, $VAR = 10, etc.).
 * - Tokenizador tolerante a palabras clave de plantilla (actor, pedtype, stat, pressed_key, defined, etc.).
 * - Eliminación de comentarios multilínea { ... }, /* ... */ y etiquetas inline.
 * - Resolución exacta de etiquetas con saltos relativos negativos Little Endian.
 * - Protección contra cierres (Crash Prevention) con terminador 0A93.
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

  // Modelos conocidos de GTA San Andreas
  private val gtaModels = mapOf(
    // Vehículos
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
   */
  fun compile(sourceCode: String): CompilationResult {
    val startTimeNanos = System.nanoTime()

    // 1. Limpieza inicial de comentarios multilínea y bloques
    val preprocessed = preprocessSource(sourceCode)

    if (preprocessed.all { it.text.isBlank() }) {
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
    var nextGlobalOffset = 1024

    fun getGlobalOffset(varName: String): Int {
      val upper = varName.uppercase().trim().removePrefix("$")
      when (upper) {
        "PLAYER_CHAR" -> return 8
        "PLAYER_ACTOR", "PLAYER_PED", "PLAYER" -> return 12
        "PLAYER_GROUP" -> return 16
        "ONMISSION" -> return 128
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
    // FASE 0: Parseo léxico y sintáctico
    // =========================================================================
    var insideHexBlock = false
    val hexBlockBytes = mutableListOf<Byte>()

    for (entry in preprocessed) {
      val lineNumber = entry.originalLine
      var clean = entry.text.trim()

      if (clean.isEmpty()) continue

      // Bloques HEX..END
      val lowerClean = clean.lowercase()
      if (lowerClean == "hex") {
        insideHexBlock = true
        hexBlockBytes.clear()
        continue
      }
      if (lowerClean == "end" && insideHexBlock) {
        insideHexBlock = false
        if (hexBlockBytes.isNotEmpty()) {
          parsedItems.add(ParsedItem.RawBytes(hexBlockBytes.toByteArray(), lineNumber))
          hexBlockBytes.clear()
        }
        continue
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
        continue
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
        continue
      }

      // Directivas del compilador Sanny Builder / CLEO (ej. {$CLEO .csa}, {$NOSAVE})
      if (clean.startsWith("{$")) {
        parsedItems.add(ParsedItem.Directive(clean, lineNumber))
        continue
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
          if (restAfter.isEmpty()) {
            continue
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
        // Formato con código hexadecimal: 0001: wait 0 ms
        opcodeHex = opcodeMatch.groupValues[1].uppercase()
        argumentsRest = if (parts.size > 1) parts[1].trim() else ""
      } else {
        // 1. Sintaxis OOP estilo Sanny Builder (ej. Player.Defined(0), Actor.Driving($PLAYER_ACTOR))
        val oopResult = resolveSannyOOP(clean)
        if (oopResult != null) {
          opcodeHex = oopResult.first
          argumentsRest = oopResult.second
        }

        // 2. Expresión matemática / asignación / comparación (ej. $VAR = 10, 0@ += 1, 2@ = 2@ + 15.0, 0@ > 5)
        if (opcodeHex == null) {
          val mathResult = resolveSannyMath(clean)
          if (mathResult != null) {
            opcodeHex = mathResult.first
            argumentsRest = mathResult.second
          }
        }

        // 3. Comandos directos y palabras clave (ej. wait 0 ms, jump @LOOP, thread 'NAME', return, etc.)
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
            rawLine = entry.text,
            type = CompilerErrorType.INVALID_OPCODE_FORMAT,
            message = "Instrucción no reconocida o formato de opcode inválido: '$firstToken'. Puedes usar formato hexadecimal (ej: '0001: wait 0 ms') o sintaxis Sanny Builder (ej: 'wait 0 ms', '0@ = 10', '\$VAR += 1', 'jump @LABEL', 'end_thread').",
            suggestion = "Revisa la instrucción. Opcodes comunes: 0001 (wait), 0A93 (end_custom_thread), 03A4 (name_thread), o expresiones como 0@ = 1."
          )
        )
      }

      var effectiveOpcodeHex = opcodeHex!!
      var opcodeInt = effectiveOpcodeHex.toInt(16)

      // 004E termina scripts del main.scm y provoca cierres al usarlo en CLEO.
      // Se acepta como alias heredado y se emite siempre el terminador CLEO seguro.
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
            rawLine = entry.text,
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
        rawLine = entry.text,
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
              rawLine = entry.text
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
      totalLines = sourceCode.lines().size,
      hexDump = hexDump,
      scriptName = detectedScriptName,
      compilationTimeMs = elapsedDurationMs
    )
  }

  private data class SourceEntry(val text: String, val originalLine: Int)

  /**
   * Preprocesador: remueve comentarios en bloque { ... } y /* ... */,
   * y expande estructuras de control de Sanny Builder (if..then..else..end, while..end).
   */
  private fun preprocessSource(source: String): List<SourceEntry> {
    val rawLines = source.lines()
    val cleanLines = mutableListOf<SourceEntry>()

    var inBlockCommentBraces = false
    var inBlockCommentSlash = false

    rawLines.forEachIndexed { idx, line ->
      val lineNum = idx + 1
      val remaining = line

      // Remover comentarios en bloque o llaves { ... } que no sean directivas {$...}
      val sb = StringBuilder()
      var i = 0
      while (i < remaining.length) {
        if (!inBlockCommentBraces && !inBlockCommentSlash) {
          // Directivas {$CLEO ...} se conservan
          if (remaining.startsWith("{$", i)) {
            val endIdx = remaining.indexOf('}', i)
            if (endIdx != -1) {
              sb.append(remaining.substring(i, endIdx + 1))
              i = endIdx + 1
              continue
            }
          }
          // Bloques de llaves { ... }
          if (remaining[i] == '{') {
            inBlockCommentBraces = true
            i++
            continue
          }
          // Bloques /* ... */
          if (remaining.startsWith("/*", i)) {
            inBlockCommentSlash = true
            i += 2
            continue
          }
          // Comentarios de línea //, ;, #
          if (remaining.startsWith("//", i) || remaining[i] == ';' || remaining[i] == '#') {
            break
          }
          sb.append(remaining[i])
          i++
        } else if (inBlockCommentBraces) {
          if (remaining[i] == '}') {
            inBlockCommentBraces = false
          }
          i++
        } else if (inBlockCommentSlash) {
          if (remaining.startsWith("*/", i)) {
            inBlockCommentSlash = false
            i += 2
          } else {
            i++
          }
        }
      }

      val cleaned = sb.toString().trim()
      if (cleaned.isNotEmpty()) {
        cleanLines.add(SourceEntry(cleaned, lineNum))
      }
    }

    // Expansión de estructuras de control (if..then..else..end, while..end)
    return expandStructuredControlFlow(cleanLines)
  }

  private sealed class ControlBlock {
    data class IfBlock(
      val falseLabel: String,
      val endLabel: String,
      var hasElse: Boolean = false,
      val originalLine: Int
    ) : ControlBlock()

    data class WhileBlock(
      val startLabel: String,
      val endLabel: String,
      val originalLine: Int
    ) : ControlBlock()
  }

  /**
   * Transforma construcciones de alto nivel de Sanny Builder:
   * if ... then ... else ... end
   * while ... end
   */
  private fun expandStructuredControlFlow(entries: List<SourceEntry>): List<SourceEntry> {
    val result = mutableListOf<SourceEntry>()
    val stack = mutableListOf<ControlBlock>()
    var labelCounter = 1

    var i = 0
    while (i < entries.size) {
      val entry = entries[i]
      val raw = entry.text.trim()
      val lower = raw.lowercase()

      when {
        // Bloque IF estructurado: 'if', 'if 0', 'if and', 'if or'
        lower == "if" || lower.startsWith("if ") -> {
          var hasThenAhead = false
          var thenIndex = -1
          for (k in (i + 1) until entries.size) {
            val kLow = entries[k].text.trim().lowercase()
            if (kLow == "then" || kLow.endsWith(" then")) {
              hasThenAhead = true
              thenIndex = k
              break
            }
            if (kLow.startsWith("if") || kLow.startsWith("while")) {
              break
            }
          }

          if (hasThenAhead) {
            val conditionCount = thenIndex - i - 1
            val ifArg = when {
              lower.contains("or") -> if (conditionCount > 0) "or $conditionCount" else "or 1"
              lower.contains("and") -> if (conditionCount > 0) "and $conditionCount" else "and 1"
              else -> if (conditionCount > 0) "$conditionCount" else "0"
            }
            val falseLbl = "__IF_FALSE_${labelCounter}"
            val endLbl = "__IF_END_${labelCounter}"
            labelCounter++

            stack.add(ControlBlock.IfBlock(falseLbl, endLbl, false, entry.originalLine))
            result.add(SourceEntry("00D6: if $ifArg", entry.originalLine))
          } else {
            val arg = raw.substring(2).trim().ifEmpty { "0" }
            val countArg = if (arg.equals("and", true) || arg.equals("or", true)) "0" else arg
            result.add(SourceEntry("00D6: if $countArg", entry.originalLine))
          }
        }

        // 'then' del bloque if estructurado
        lower == "then" || lower.endsWith(" then") -> {
          val top = stack.lastOrNull()
          if (top is ControlBlock.IfBlock) {
            result.add(SourceEntry("004D: jump_if_false @${top.falseLabel}", entry.originalLine))
          }
        }

        // 'else' del bloque if estructurado
        lower == "else" -> {
          val top = stack.lastOrNull()
          if (top is ControlBlock.IfBlock) {
            top.hasElse = true
            result.add(SourceEntry("0002: jump @${top.endLabel}", entry.originalLine))
            result.add(SourceEntry(":${top.falseLabel}", entry.originalLine))
          } else {
            result.add(entry)
          }
        }

        // 'while' estructurado
        lower.startsWith("while ") || lower == "while" -> {
          val startLbl = "__WHILE_START_${labelCounter}"
          val endLbl = "__WHILE_END_${labelCounter}"
          labelCounter++
          stack.add(ControlBlock.WhileBlock(startLbl, endLbl, entry.originalLine))
          result.add(SourceEntry(":$startLbl", entry.originalLine))

          val rest = raw.substring(5).trim()
          if (rest.isNotEmpty()) {
            result.add(SourceEntry(rest, entry.originalLine))
            result.add(SourceEntry("004D: jump_if_false @$endLbl", entry.originalLine))
          }
        }

        // 'end' de estructura de control (if, while)
        lower == "end" -> {
          if (stack.isNotEmpty()) {
            when (val top = stack.removeAt(stack.lastIndex)) {
              is ControlBlock.IfBlock -> {
                if (top.hasElse) {
                  result.add(SourceEntry(":${top.endLabel}", entry.originalLine))
                } else {
                  result.add(SourceEntry(":${top.falseLabel}", entry.originalLine))
                }
              }
              is ControlBlock.WhileBlock -> {
                result.add(SourceEntry("0002: jump @${top.startLabel}", entry.originalLine))
                result.add(SourceEntry(":${top.endLabel}", entry.originalLine))
              }
            }
          }
        }

        else -> {
          result.add(entry)
        }
      }
      i++
    }

    return result
  }

  /**
   * Resuelve sintaxis orientada a objetos (OOP) de Sanny Builder.
   */
  private fun resolveSannyOOP(line: String): Pair<String, String>? {
    val clean = line.trim()

    // 1. Player.Defined(0) / Player.Defined($PLAYER_CHAR)
    val playerDefMatch = Regex("^(?:Player|player)\\.Defined\\s*\\((.*?)\\)$", RegexOption.IGNORE_CASE).find(clean)
    if (playerDefMatch != null) {
      val arg = playerDefMatch.groupValues[1].trim().ifEmpty { "0" }
      return Pair("0256", arg)
    }

    // 2. Actor.Driving($PLAYER_ACTOR) / Actor.Driving(0@)
    val actorDrivingMatch = Regex("^(?:Actor|actor|Char|char)\\.Driving\\s*\\((.*?)\\)$", RegexOption.IGNORE_CASE).find(clean)
    if (actorDrivingMatch != null) {
      val arg = actorDrivingMatch.groupValues[1].trim().ifEmpty { "\$PLAYER_ACTOR" }
      return Pair("00DF", arg)
    }

    // 3. Actor.Dead($PLAYER_ACTOR)
    val actorDeadMatch = Regex("^(?:Actor|actor|Char|char)\\.Dead\\s*\\((.*?)\\)$", RegexOption.IGNORE_CASE).find(clean)
    if (actorDeadMatch != null) {
      val arg = actorDeadMatch.groupValues[1].trim()
      return Pair("0118", arg)
    }

    // 4. Car.Dead($CAR) / Vehicle.Dead($CAR)
    val carDeadMatch = Regex("^(?:Car|car|Vehicle|vehicle)\\.Dead\\s*\\((.*?)\\)$", RegexOption.IGNORE_CASE).find(clean)
    if (carDeadMatch != null) {
      val arg = carDeadMatch.groupValues[1].trim()
      return Pair("0119", arg)
    }

    // 5. Actor.StorePos($PLAYER_ACTOR, 0@, 1@, 2@)
    val actorPosMatch = Regex("^(?:Actor|actor|Char|char)\\.StorePos\\s*\\((.*?)\\)$", RegexOption.IGNORE_CASE).find(clean)
    if (actorPosMatch != null) {
      val args = actorPosMatch.groupValues[1].split(",").map { it.trim() }.joinToString(" ")
      return Pair("04C4", args)
    }

    // 6. Camera.Restore
    if (clean.equals("Camera.Restore", ignoreCase = true) || clean.equals("Camera.Restore()", ignoreCase = true)) {
      return Pair("015F", "")
    }

    return null
  }

  /**
   * Resuelve expresiones matemáticas y asignaciones de Sanny Builder.
   */
  private fun resolveSannyMath(cleanLine: String): Pair<String, String>? {
    val clean = cleanLine.trim()

    // Asignación compuesta: 2@ = 2@ + 15.0
    val compAssignMatch = Regex("^(\\$?[A-Za-z0-9_@]+)\\s*=\\s*\\1\\s*([+\\-*/])\\s*(.+)$").find(clean)
    if (compAssignMatch != null) {
      val dest = compAssignMatch.groupValues[1].trim()
      val sign = compAssignMatch.groupValues[2].trim()
      val operand = compAssignMatch.groupValues[3].trim()
      val op = "${sign}="
      val opcodeHex = resolveSannyMathOpcode(dest, op, operand)
      if (opcodeHex != null) {
        return Pair(opcodeHex, "$dest $operand")
      }
    }

    // Operadores estándar: $VAR = 10, 0@ += 5.0, 0@ >= 60, 0@ == 1
    val sannyMathMatch = Regex("^(\\$?[A-Za-z0-9_@]+)\\s*(==|>=|<=|!=|<>|\\+=|-=|\\*=|/=|=(?!=)|>|<)\\s*(.+)$").find(clean)
    if (sannyMathMatch != null) {
      val left = sannyMathMatch.groupValues[1].trim()
      val op = sannyMathMatch.groupValues[2].trim()
      val right = sannyMathMatch.groupValues[3].trim()
      val resolvedHex = resolveSannyMathOpcode(left, op, right)
      if (resolvedHex != null) {
        return Pair(resolvedHex, "$left $right")
      }
    }

    return null
  }

  fun inferScriptName(sourceCode: String, targetExtension: String = "csa"): String {
    val cleanExt = targetExtension.trim().removePrefix(".").lowercase().ifEmpty { "csa" }
    val lines = sourceCode.lines()

    // 1. Directivas explícitas {$NAME mi_script}
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

    // 2. Opcode 03A4: name_thread 'NOMBRE' o directiva thread 'NOMBRE'
    for (line in lines) {
      val t = line.trim()
      val threadMatch = Regex("(?:03A4\\s*:\\s*name_thread|name_thread|thread)\\s*['\"]([A-Za-z0-9_\\-]+)['\"]", RegexOption.IGNORE_CASE).find(t)
      if (threadMatch != null) {
        val raw = threadMatch.groupValues[1].trim()
        val lower = raw.lowercase()
        if (lower !in listOf("main", "thread", "script", "noname") && raw.isNotEmpty()) {
          val sanitized = sanitizeFileName(raw)
          if (sanitized.isNotEmpty()) return "$sanitized.$cleanExt"
        }
      }
    }

    // 3. Primer comentario relevante
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

    // 4. Inferencia por contenido semántico
    val codeLower = sourceCode.lowercase()
    if (codeLower.contains("0056") || codeLower.contains("make_actor_say") || codeLower.contains("voice")) {
      return if (cleanExt == "csi") "voice_dialogue.csi" else "voice_mod.csa"
    }
    if (codeLower.contains("0de") || codeLower.contains("touch") || codeLower.contains("swipe")) {
      return if (cleanExt == "csi") "touch_actions.csi" else "touch_controls.csa"
    }
    if (codeLower.contains("0dd") || codeLower.contains("create_menu")) {
      return if (cleanExt == "csi") "cleo_menu.csi" else "custom_menu.csa"
    }
    if (codeLower.contains("0109") || codeLower.contains("010b") || codeLower.contains("money")) {
      return if (cleanExt == "csi") "money_menu.csi" else "money_mod.csa"
    }
    if (codeLower.contains("00a5") || codeLower.contains("create_car") || codeLower.contains("infernus")) {
      return if (cleanExt == "csi") "car_spawner.csi" else "auto_vehicle.csa"
    }
    if (codeLower.contains("weapon") || codeLower.contains("01b2") || codeLower.contains("minigun")) {
      return if (cleanExt == "csi") "weapons_menu.csi" else "weapons_mod.csa"
    }
    if (codeLower.contains("set_actor_coordinates") || codeLower.contains("00a1") || codeLower.contains("teleport")) {
      return if (cleanExt == "csi") "teleport_menu.csi" else "teleport.csa"
    }
    if (codeLower.contains("02ab") || codeLower.contains("godmode")) {
      return "godmode.$cleanExt"
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
    is ScriptParam.LabelRef -> 1 + 4
    is ScriptParam.LocalVar -> 1 + 2
    is ScriptParam.GlobalVar -> 1 + 2
    is ScriptParam.IntVal -> {
      val v = param.value
      if (v in -128..127) 1 + 1
      else if (v in -32768..32767) 1 + 2
      else 1 + 4
    }
    is ScriptParam.FloatVal -> 1 + 4
    is ScriptParam.ShortStringVal -> 1 + 8
    is ScriptParam.MediumStringVal -> 1 + 16
    is ScriptParam.VarStringVal -> 1 + 1 + param.text.toByteArray(Charsets.ISO_8859_1).size
  }

  private sealed class ParseResult {
    data class Success(val params: List<ScriptParam>) : ParseResult()
    data class Error(val error: CompilationError) : ParseResult()
  }

  /**
   * Tokenizador inteligente de argumentos con soporte universal para palabras clave
   * y sintaxis de plantilla de Sanny Builder.
   */
  private fun parseInstructionArguments(
    opcodeDef: OpcodeDef,
    opcodeInt: Int,
    argumentsRest: String,
    lineNumber: Int,
    rawLine: String,
    getGlobalOffset: (String) -> Int
  ): ParseResult {
    val cleanOpcode = opcodeInt and 0x7FFF

    // Opcodes sin parámetros
    when (cleanOpcode) {
      0x0000, 0x004E, 0x0051, 0x00BE, 0x00BF, 0x038B, 0x015F, 0x016A, 0x0249, 0x06FD, 0x0DD8, 0x0DDD, 0x0395, 0x0A93 -> {
        return ParseResult.Success(emptyList())
      }

      // 0001: wait X ms / 0001: 250 / wait 1000ms / wait 2s
      0x0001 -> {
        val cleanArgs = argumentsRest.replace("ms", "", ignoreCase = true)
          .replace("sec", "", ignoreCase = true)
          .replace("s", "", ignoreCase = true)
          .trim()
        val tokens = cleanArgs.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var timeParam: ScriptParam? = null
        for (token in tokens) {
          if (token.equals("wait", true)) continue
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

      // 03A4: name_thread 'MYMOD' / thread 'MYMOD'
      0x03A4 -> {
        val stringMatch = Regex("['\"]([^'\"]+)['\"]").find(argumentsRest)
        val name = stringMatch?.groupValues?.get(1)
          ?: argumentsRest.substringAfter("name_thread").substringAfter("thread").trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")

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
        val text = stringMatch?.groupValues?.get(1) ?: argumentsRest.substringAfter("show_text_box").trim().removeSurrounding("\"", "\"")
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

    // Extraer cadenas con comillas
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

    for (token in rawTokens) {
      val trimmed = token.trim()
      if (trimmed.isEmpty()) continue

      // Placeholder de cadena de texto
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

      // 4. Float (ej. 0.0, -1666.0, 13.5f, 5.0)
      val floatClean = trimmed.removeSuffix("f").removeSuffix("F")
      if (floatClean.contains(".") && floatClean.toFloatOrNull() != null) {
        params.add(ScriptParam.FloatVal(floatClean.toFloat()))
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

      // 7. Tiempo con sufijo ms/s (ej. 1000ms -> 1000)
      val timeClean = trimmed.lowercase().removeSuffix("ms").removeSuffix("sec").removeSuffix("s")
      val timeInt = timeClean.toIntOrNull()
      if (timeInt != null && (trimmed.endsWith("ms", true) || trimmed.endsWith("sec", true) || trimmed.endsWith("s", true))) {
        params.add(ScriptParam.IntVal(timeInt))
        continue
      }

      // 8. Entero decimal estándar (ej. 250, -1, 1000)
      val intVal = trimmed.toIntOrNull()
      if (intVal != null) {
        params.add(ScriptParam.IntVal(intVal))
        continue
      }

      // 9. Booleano
      if (trimmed.equals("true", ignoreCase = true)) {
        params.add(ScriptParam.IntVal(1))
        continue
      }
      if (trimmed.equals("false", ignoreCase = true)) {
        params.add(ScriptParam.IntVal(0))
        continue
      }

      // 10. Modelo reconocido directamente por nombre (ej. INFERNUS, HYDRA, AK47)
      val knownModelId = gtaModels[trimmed.uppercase()]
      if (knownModelId != null) {
        params.add(ScriptParam.IntVal(knownModelId))
        continue
      }

      // Si es una palabra puramente alfabética que forma parte de la sintaxis de plantilla de Sanny Builder
      // (ej. 'actor', 'defined', 'stat', 'pressed_key', 'in_any_car', 'stone', 'time', 'style', 'printer', 'weapon', 'ammo', 'pedtype', etc.)
      // se ignora como palabra de adorno sintáctico para no rechazar código válido.
    }

    // Si hubo una asignación de variable a la izquierda (ej. $CAR = create_car ...),
    // en SCM esa variable de destino se almacena al FINAL de los parámetros
    if (assignmentDestToken != null) {
      if (assignmentDestToken.startsWith("$")) {
        val varName = assignmentDestToken.removePrefix("$")
        params.add(ScriptParam.GlobalVar(getGlobalOffset(varName), varName))
      } else {
        val localIdx = assignmentDestToken.substringBefore("@").toIntOrNull() ?: 0
        params.add(ScriptParam.LocalVar(localIdx))
      }
    }

    // Para opcodes flexibles (como if 00D6 sin parámetros que toma 0 por defecto)
    if (cleanOpcode == 0x00D6 && params.isEmpty()) {
      params.add(ScriptParam.IntVal(0))
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
        else if (rightIsFloat) "0021" else "0039"
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

    // 1. Directiva thread 'NAME'
    if (low.startsWith("thread ") || low == "thread") {
      val args = cleanLine.substring(6).trim()
      return Pair("03A4", args)
    }

    // 2. wait X ms / wait 1000ms / wait
    if (low.startsWith("wait ") || low == "wait") {
      val args = cleanLine.substring(4).trim().ifEmpty { "0" }
      return Pair("0001", args)
    }

    // 3. jump / goto
    if (low.startsWith("jump ") || low.startsWith("goto ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("0002", args)
    }

    // 4. jf / jump_if_false
    if (low.startsWith("jf ") || low.startsWith("jump_if_false ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("004D", args)
    }

    // 5. end_thread / end_custom_thread / terminate_this_script
    if (low in listOf("end_thread", "end_custom_thread", "end_custom_script", "terminate_this_custom_script", "terminate_this_script")) {
      return Pair("0A93", "")
    }

    // 6. gosub
    if (low.startsWith("gosub ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("0050", args)
    }

    // 7. return / return 0 / return 1
    if (low == "return" || low.startsWith("return ")) {
      return Pair("0051", "")
    }

    // 8. if / if 0 / if and / if or
    if (low.startsWith("if ") || low == "if") {
      val args = cleanLine.substring(2).trim().ifEmpty { "0" }
      return Pair("00D6", args)
    }

    // 9. create_thread
    if (low.startsWith("create_thread ")) {
      val args = cleanLine.substring(13).trim()
      return Pair("00D7", args)
    }

    // 10. name_thread
    if (low.startsWith("name_thread ")) {
      val args = cleanLine.substring(11).trim()
      return Pair("03A4", args)
    }

    // 11. nop
    if (low == "nop") {
      return Pair("0000", "")
    }

    // 12. fade
    if (low.startsWith("fade ") || low.startsWith("fade_screen ")) {
      val args = cleanLine.split(Regex("\\s+"), limit = 2).getOrNull(1)?.trim() ?: ""
      return Pair("016A", args)
    }

    // 13. Comprobación por nombre de comando oficial o personalizado
    val firstWord = cleanLine.split(Regex("\\s+"), limit = 2)[0]
    val cmdDef = CleoOpcodeDatabase.findByName(firstWord)
    if (cmdDef != null) {
      val rest = cleanLine.substring(firstWord.length).trim()
      return Pair(cmdDef.hexString, rest)
    }

    return null
  }
}
