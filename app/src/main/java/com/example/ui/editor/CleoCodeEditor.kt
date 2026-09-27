package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Resaltado de sintaxis para opcodes y palabras clave de GTA San Andreas CLEO.
 */
class CleoSyntaxVisualTransformation : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val raw = text.text
    val builder = AnnotatedString.Builder(raw)

    // Opcodes de GTA SA (ej. 0001:, 004E:, 03A4:)
    val opcodeRegex = Regex("(?m)^\\s*([0-9A-Fa-f]{4}:)")
    opcodeRegex.findAll(raw).forEach { matchResult ->
      val group = matchResult.groups[1]
      if (group != null) {
        builder.addStyle(
          SpanStyle(
            color = Color(0xFF1565C0),
            fontWeight = FontWeight.Bold
          ),
          group.range.first,
          group.range.last + 1
        )
      }
    }

    // Comentarios (// ..., ;, /* ... */, { ... })
    val commentRegex = Regex("//.*|;.*|/\\*.*?\\*/|\\{.*?\\}")
    commentRegex.findAll(raw).forEach { matchResult ->
      builder.addStyle(
        SpanStyle(
          color = Color(0xFF94A3B8),
          fontStyle = FontStyle.Italic
        ),
        matchResult.range.first,
        matchResult.range.last + 1
      )
    }

    // Palabras clave CLEO y Sanny Builder
    val keywords = setOf(
      "wait", "ms", "sec", "end_thread", "end_custom_thread", "end_custom_script",
      "terminate_this_script", "terminate_this_custom_script", "create_thread",
      "name_thread", "thread", "jump", "goto", "jf", "jump_if_false", "gosub", "return",
      "if", "then", "else", "end", "while", "repeat", "until", "and", "or", "not",
      "hex", "fade", "fade_screen", "nop"
    )
    val wordRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b")
    wordRegex.findAll(raw).forEach { matchResult ->
      val word = matchResult.value.lowercase()
      if (keywords.contains(word)) {
        builder.addStyle(
          SpanStyle(
            color = Color(0xFF4338CA),
            fontWeight = FontWeight.SemiBold
          ),
          matchResult.range.first,
          matchResult.range.last + 1
        )
      }
    }

    // Clases OOP (Player, Actor, Char, Car, Vehicle, Camera, Text)
    val oopRegex = Regex("\\b(Player|Actor|Char|Car|Vehicle|Camera|Text|Audio|Memory|File)\\.[A-Za-z0-9_]+")
    oopRegex.findAll(raw).forEach { matchResult ->
      builder.addStyle(
        SpanStyle(
          color = Color(0xFF9333EA),
          fontWeight = FontWeight.Bold
        ),
        matchResult.range.first,
        matchResult.range.last + 1
      )
    }

    // Variables locales (0@) y globales ($PLAYER_CHAR, $VAR)
    val varRegex = Regex("(\\$\\w+|\\d+@[vs]?)")
    varRegex.findAll(raw).forEach { matchResult ->
      builder.addStyle(
        SpanStyle(
          color = Color(0xFFB45309),
          fontWeight = FontWeight.Medium
        ),
        matchResult.range.first,
        matchResult.range.last + 1
      )
    }

    // Números
    val numberRegex = Regex("\\b\\d+(?:\\.\\d+)?(?:f|ms|s)?\\b")
    numberRegex.findAll(raw).forEach { matchResult ->
      builder.addStyle(
        SpanStyle(
          color = Color(0xFF0F766E),
          fontWeight = FontWeight.Medium
        ),
        matchResult.range.first,
        matchResult.range.last + 1
      )
    }

    return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
  }
}

@Composable
fun CleoCodeEditor(
  code: TextFieldValue,
  onCodeChange: (TextFieldValue) -> Unit,
  errorLine: Int? = null,
  modifier: Modifier = Modifier
) {
  val lines = remember(code.text) {
    val count = code.text.count { it == '\n' } + 1
    (1..count).toList()
  }

  val verticalScrollState = rememberScrollState()
  val horizontalScrollState = rememberScrollState()

  val editorTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 22.sp,
    color = Color(0xFF1E293B)
  )

  val gutterTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 22.sp,
    textAlign = TextAlign.End,
    color = Color(0xFF94A3B8)
  )

  val errorGutterTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 22.sp,
    textAlign = TextAlign.End,
    fontWeight = FontWeight.Bold,
    color = Color(0xFFDC2626)
  )

  // Área limpia directa sin barras superiores innecesarias
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.White)
      .verticalScroll(verticalScrollState)
  ) {
    // Numeración de líneas con indicador de error si aplica
    Column(
      modifier = Modifier
        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        .width(32.dp)
    ) {
      lines.forEach { lineNum ->
        val isError = lineNum == errorLine
        Text(
          text = if (isError) "● $lineNum" else "$lineNum",
          style = if (isError) errorGutterTextStyle else gutterTextStyle,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    // Divisor sutil
    Box(
      modifier = Modifier
        .width(1.dp)
        .fillMaxHeight()
        .background(Color(0xFFF1F5F9))
    )

    // Editor de texto
    Box(
      modifier = Modifier
        .weight(1f)
        .horizontalScroll(horizontalScrollState)
        .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
      if (code.text.isEmpty()) {
        Text(
          text = stringResource(R.string.editor_placeholder),
          style = editorTextStyle.copy(color = Color(0xFFCBD5E1))
        )
      }

      BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        textStyle = editorTextStyle,
        cursorBrush = SolidColor(Color(0xFF1976D2)),
        visualTransformation = CleoSyntaxVisualTransformation(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("code_editor_input")
      )
    }
  }
}
