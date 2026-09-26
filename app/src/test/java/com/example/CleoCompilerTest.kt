package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.compiler.CleoCompiler
import com.example.compiler.CleoOpcodeDatabase
import com.example.compiler.CompilationResult
import com.example.compiler.CompilerErrorType
import com.example.compiler.OpcodeDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CleoCompilerTest {

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    CleoOpcodeDatabase.loadFromAssets(context)
  }

  @Test
  fun `valid cleo script compiles successfully`() {
    val script = """
      // Prueba de script
      0001: wait 0 ms
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar con éxito", result is CompilationResult.Success)

    val success = result as CompilationResult.Success
    assertEquals(2, success.opcodesCompiled)
    assertTrue("Debe generar bytes", success.bytecode.isNotEmpty())
    // 0001 wait 0 -> 01 00 04 00 (4 bytes), 004E -> 93 0A (2 bytes CLEO terminator) = 6 bytes
    assertEquals("01 00 04 00 93 0A", success.hexDump)
  }

  @Test
  fun `unknown opcode returns failure with line number`() {
    val script = """
      0001: wait 0 ms
      9999: inventado
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe fallar", result is CompilationResult.Failure)

    val failure = result as CompilationResult.Failure
    assertEquals(2, failure.error.line)
    assertEquals(CompilerErrorType.UNKNOWN_OPCODE, failure.error.type)
  }

  @Test
  fun `invalid opcode format returns failure`() {
    val script = """
      0001: wait 0 ms
      malformado: codigo
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe fallar", result is CompilationResult.Failure)

    val failure = result as CompilationResult.Failure
    assertEquals(2, failure.error.line)
    assertEquals(CompilerErrorType.INVALID_OPCODE_FORMAT, failure.error.type)
  }

  @Test
  fun `missing parameters in opcode returns failure`() {
    val script = """
      0001: wait
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe fallar", result is CompilationResult.Failure)

    val failure = result as CompilationResult.Failure
    assertEquals(1, failure.error.line)
    assertEquals(CompilerErrorType.INVALID_PARAMETERS, failure.error.type)
  }

  @Test
  fun `empty source returns empty error`() {
    val script = "   \n// solo comentario\n   "
    val result = CleoCompiler.compile(script)
    assertTrue("Debe fallar por código vacío", result is CompilationResult.Failure)
    val failure = result as CompilationResult.Failure
    assertEquals(CompilerErrorType.EMPTY_SOURCE, failure.error.type)
  }

  @Test
  fun `database contains at least 1000 valid opcodes and category checks pass`() {
    val count = com.example.compiler.CleoOpcodeDatabase.count()
    assertTrue("La base de datos debe contener al menos 1000 opcodes (actual: $count)", count >= 1000)

    val worldMissionsOpcodes = com.example.compiler.CleoOpcodeDatabase.getAll().filter {
      it.category.equals("Mundo, Interiores & Misiones", ignoreCase = true)
    }
    assertTrue("Mundo, Interiores & Misiones debe contener al menos 200 opcodes (actual: ${worldMissionsOpcodes.size})", worldMissionsOpcodes.size >= 200)

    val cameraHudEffectsOpcodes = com.example.compiler.CleoOpcodeDatabase.getAll().filter {
      it.category.equals("Cámara, HUD, Textos & Efectos", ignoreCase = true)
    }
    assertTrue("Cámara, HUD, Textos & Efectos debe contener al menos 200 opcodes (actual: ${cameraHudEffectsOpcodes.size})", cameraHudEffectsOpcodes.size >= 200)

    // Comprobar opcodes emblemáticos de GTA SA
    assertTrue("Debe contener 0001 (wait)", com.example.compiler.CleoOpcodeDatabase.findByHex("0001") != null)
    assertTrue("Debe contener 0213 (create_pickup)", com.example.compiler.CleoOpcodeDatabase.findByHex("0213") != null)
    assertTrue("Debe contener 0107 (create_object)", com.example.compiler.CleoOpcodeDatabase.findByHex("0107") != null)
    assertTrue("Debe contener 04BB (set_current_interior)", com.example.compiler.CleoOpcodeDatabase.findByHex("04BB") != null)
    assertTrue("Debe contener 01F5 (create_checkpoint)", com.example.compiler.CleoOpcodeDatabase.findByHex("01F5") != null)
    assertTrue("Debe contener 00BB (print_with_number_now)", com.example.compiler.CleoOpcodeDatabase.findByHex("00BB") != null)
    assertTrue("Debe contener 015C (set_fixed_camera_position)", com.example.compiler.CleoOpcodeDatabase.findByHex("015C") != null)
    assertTrue("Debe contener 045A (draw_rect)", com.example.compiler.CleoOpcodeDatabase.findByHex("045A") != null)
    assertTrue("Debe contener 024F (create_corona)", com.example.compiler.CleoOpcodeDatabase.findByHex("024F") != null)
    assertTrue("Debe contener 04FC (set_night_vision)", com.example.compiler.CleoOpcodeDatabase.findByHex("04FC") != null)
  }

  @Test
  fun `custom opcode is recognized and compiled`() {
    val customDef = com.example.compiler.OpcodeDef(
      opcode = 0x0A90,
      hexString = "0A90",
      commandName = "call_function",
      description = "Llama funcion interna",
      minParams = 1,
      maxParams = 1,
      example = "0A90: call_function 0x123456"
    )
    com.example.compiler.CleoOpcodeDatabase.registerCustom(customDef)

    val script = """
      0001: wait 0 ms
      0A90: call_function 100
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar con éxito usando el opcode personalizado", result is CompilationResult.Success)
  }

  @Test
  fun `long cleo script compiles without errors`() {
    val script = """
      // ========================================================
      // COMPILER STUDIO - SCRIPT CLEO COMPLETO (.CS)
      // MOD: SUPER CJ - SALUD INFINITA, ARMAS Y VEHICULOS
      // ========================================================

      03A4: name_thread 'SUPERCJ'
      0001: wait 1000 ms
      0ACA: show_text_box "Compiler Studio: Super CJ Activado"

      0109: player ${'$'}PLAYER_CHAR add_money 999999
      01B6: set_weather 1
      014D: set_actor ${'$'}PLAYER_ACTOR armour 100
      01B2: give_actor ${'$'}PLAYER_ACTOR weapon 24 ammo 250
      05E2: give_actor ${'$'}PLAYER_ACTOR weapon 31 ammo 500

      :MAIN_LOOP
      0001: wait 250 ms
      014D: set_actor ${'$'}PLAYER_ACTOR armour 100
      02AB: set_actor ${'$'}PLAYER_ACTOR immunities 1 1 1 1 1
      0050: gosub @SUB_REPAIR_VEHICLE
      0002: jump @MAIN_LOOP

      :SUB_REPAIR_VEHICLE
      0001: wait 50 ms
      0229: set_car ${'$'}CAR health 1000
      0051: return

      :CLEO_TERMINATE
      0001: wait 500 ms
      0ACA: show_text_box "Mod Desactivado"
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar exitosamente: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertTrue("Debe generar bytecode válido", success.bytecode.isNotEmpty())
    assertTrue("Debe compilar al menos 15 opcodes", success.opcodesCompiled >= 15)
  }

  @Test
  fun `jump to label generates negative relative offset for CLEO`() {
    val script = """
      :LOOP
      0001: wait 0 ms
      0002: jump @LOOP
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    // Offset de :LOOP es 0.
    // 0001 wait 0 ms: 01 00 (opcode) 04 00 (Int8 0) = 4 bytes
    // 0002 jump @LOOP: 02 00 (opcode) 01 (Int32) 00 00 00 00 (-0 = 0)
    assertEquals("01 00 04 00 02 00 01 00 00 00 00", success.hexDump)
  }

  @Test
  fun `undefined label returns syntax error`() {
    val script = """
      0001: wait 0 ms
      0002: jump @NOT_EXIST
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe fallar", result is CompilationResult.Failure)
    val failure = result as CompilationResult.Failure
    assertEquals(CompilerErrorType.SYNTAX_ERROR, failure.error.type)
  }

  @Test
  fun `missing end_thread automatically protected to avoid game crash`() {
    val script = """
      0001: wait 250 ms
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    // 0001 wait 250: 01 00 05 FA 00 (250 cabe en Int16: 0x05 + 0x00FA) + terminador seguro 93 0A
    assertTrue("Debe terminar en 93 0A para proteger el juego", success.hexDump.endsWith("93 0A"))
  }

  @Test
  fun `negated opcode with NOT prefix sets bit 15`() {
    val script = """
      0001: wait 0 ms
      NOT 00DF: is_char_in_any_car ${'$'}PLAYER_ACTOR
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar condición negada", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    // 00DF or 0x8000 = 0x80DF -> Little endian: DF 80
    assertTrue("Debe contener DF 80", success.hexDump.contains("DF 80"))
  }

  @Test
  fun `sanny builder high level syntax compiles arithmetic and comparisons`() {
    val script = """
      wait 0 ms
      0@ = 10
      0@ += 5
      0@ -= 2
      0@ *= 3
      0@ /= 2
      0@ > 10
      0@ >= 10
      0@ == 10
      ${'$'}VAR = 100
      ${'$'}VAR += 50
      end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar sintaxis de Sanny Builder: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertTrue("Debe contener al menos 12 instrucciones", success.opcodesCompiled >= 12)
    // Comprobar que terminó en 93 0A (terminador CLEO seguro)
    assertTrue("Debe contener end_thread", success.hexDump.endsWith("93 0A"))
  }

  @Test
  fun `sanny builder high level flow jumps and labels compile correctly`() {
    val script = """
      :LOOP
      wait 100 ms
      0@ += 1
      jump @LOOP
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar bucle con etiquetas", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertTrue("Debe generar bytecode no vacío", success.bytecode.isNotEmpty())
  }

  @Test
  fun `touch gestures controls and money opcodes compile correctly`() {
    val script = """
      03A4: name_thread 'TOUCHMOD'
      wait 0 ms
      // Opcodes táctiles y gestos de Android
      0DE0: is_touch_point_pressed 5
      0DE1: get_touch_point_state 1 0@
      0DE2: get_touch_point_pos 1 1@ 2@
      0DE4: get_touch_gesture 3@
      00E1: is_button_pressed 0 15
      01B4: set_player ${'$'}PLAYER_CHAR can_move 1
      // Opcodes de dinero
      0109: player ${'$'}PLAYER_CHAR add_money 50000
      010A: player ${'$'}PLAYER_CHAR remove_money 500
      010B: player ${'$'}PLAYER_CHAR set_money 99999999
      010E: player ${'$'}PLAYER_CHAR get_money 4@
      0150: show_money 1
      06FD: play_cash_register_sound
      // Opcodes de menú táctil CLEO Android
      0DD8: is_cleo_android_menu_active
      0DD9: show_cleo_android_menu 1
      0DDA: set_cleo_menu_title "MENU MOD"
      0DDD: close_cleo_android_menu
      end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar script táctil, dinero y menú: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertTrue("Debe compilar todas las instrucciones", success.opcodesCompiled >= 15)
  }

  @Test
  fun `smart script naming infers name correctly from code or directives`() {
    // 1. Directiva {$NAME ...}
    val withDirective = "{\$NAME drift_master}\n0001: wait 0 ms"
    assertEquals("drift_master.csa", CleoCompiler.inferScriptName(withDirective, "csa"))
    assertEquals("drift_master.csi", CleoCompiler.inferScriptName(withDirective, "csi"))

    // 2. Opcode name_thread
    val withThread = "03A4: name_thread 'NITRO'\n0001: wait 0 ms"
    assertEquals("nitro.csa", CleoCompiler.inferScriptName(withThread, "csa"))

    // 3. Comentario de título
    val withComment = "// title: super_jump\n0001: wait 0 ms"
    assertEquals("super_jump.csa", CleoCompiler.inferScriptName(withComment, "csa"))

    // 4. Inferencia por contenido: dinero
    val moneyScript = "0109: player ${'$'}PLAYER_CHAR add_money 10000\n0001: wait 0 ms"
    assertEquals("money_mod.csa", CleoCompiler.inferScriptName(moneyScript, "csa"))
    assertEquals("money_menu.csi", CleoCompiler.inferScriptName(moneyScript, "csi"))

    // 5. Inferencia por contenido: táctil
    val touchScript = "0DE0: is_touch_point_pressed 5\n0DE4: get_touch_gesture 0@"
    assertEquals("touch_controls.csa", CleoCompiler.inferScriptName(touchScript, "csa"))
    assertEquals("touch_actions.csi", CleoCompiler.inferScriptName(touchScript, "csi"))

    // 6. Inferencia por contenido: menú CLEO
    val menuScript = "0DD9: show_cleo_android_menu 1\n0DDA: set_cleo_menu_title 'MODS'"
    assertEquals("cleo_menu.csi", CleoCompiler.inferScriptName(menuScript, "csi"))

    // 7. Inferencia por contenido: voz y diálogo (0056)
    val voiceScript = "0056: make_actor_say ${'$'}PLAYER_ACTOR phrase 1\n0001: wait 0 ms"
    assertEquals("voice_mod.csa", CleoCompiler.inferScriptName(voiceScript, "csa"))
    assertEquals("voice_dialogue.csi", CleoCompiler.inferScriptName(voiceScript, "csi"))
  }

  @Test
  fun `opcode 0056 and audio speech tasks compile successfully`() {
    val script = """
      03A4: name_thread 'SPEECH'
      wait 0 ms
      // 0056: make_actor_say
      0056: make_actor_say ${'$'}PLAYER_ACTOR phrase 1
      0097: make_actor_say_ambient ${'$'}PLAYER_ACTOR 5
      018C: play_sound 1052 at 0.0 0.0 0.0
      0394: play_music 1
      0775: set_radio_station 4
      0606: task_stand_still ${'$'}PLAYER_ACTOR 5000 ms
      0608: task_jump ${'$'}PLAYER_ACTOR 1
      0611: task_hands_up ${'$'}PLAYER_ACTOR 3000 ms
      0643: task_leave_vehicle ${'$'}PLAYER_ACTOR
      06E5: task_die ${'$'}PLAYER_ACTOR
      end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar opcode 0056 y audio: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertTrue("Debe compilar al menos 10 opcodes", success.opcodesCompiled >= 10)

    val op0056 = com.example.compiler.CleoOpcodeDatabase.findByHex("0056")
    assertTrue("0056 debe existir en la base de datos", op0056 != null)
    assertEquals("make_actor_say", op0056?.commandName)
  }

  @Test
  fun `database reached 2000 opcodes milestone and final batch opcodes are present`() {
    val totalCount = com.example.compiler.CleoOpcodeDatabase.count()
    assertTrue("La base de datos debe contener al menos 2000 opcodes (actual: $totalCount)", totalCount >= 2000)

    // Validar muestras del lote 1
    assertTrue("Debe contener 0DE6 (get_touch_pressure)", com.example.compiler.CleoOpcodeDatabase.findByHex("0DE6") != null)
    assertTrue("Debe contener 0DE7 (is_touch_double_tap)", com.example.compiler.CleoOpcodeDatabase.findByHex("0DE7") != null)
    assertTrue("Debe contener 0DE8 (get_multi_touch_count)", com.example.compiler.CleoOpcodeDatabase.findByHex("0DE8") != null)
    assertTrue("Debe contener 0DEA (vibrate_device)", com.example.compiler.CleoOpcodeDatabase.findByHex("0DEA") != null)

    // Validar muestras del lote 2
    assertTrue("Debe contener 02E7 (start_cutscene)", com.example.compiler.CleoOpcodeDatabase.findByHex("02E7") != null)
    assertTrue("Debe contener 031A (remove_all_fires)", com.example.compiler.CleoOpcodeDatabase.findByHex("031A") != null)
    assertTrue("Debe contener 04DB (exit_rc_mode)", com.example.compiler.CleoOpcodeDatabase.findByHex("04DB") != null)
    assertTrue("Debe contener 0793 (save_player_clothes)", com.example.compiler.CleoOpcodeDatabase.findByHex("0793") != null)

    // Validar muestras del lote 3
    assertTrue("Debe contener 0094 (abs_int)", com.example.compiler.CleoOpcodeDatabase.findByHex("0094") != null)
    assertTrue("Debe contener 0096 (abs_float)", com.example.compiler.CleoOpcodeDatabase.findByHex("0096") != null)
    assertTrue("Debe contener 0913 (run_external_script)", com.example.compiler.CleoOpcodeDatabase.findByHex("0913") != null)

    // Validar muestras del lote 4 (CLEO avanzado y utilidades finales hacia los 2000)
    assertTrue("Debe contener 0AA0 (gosub_if_false)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AA0") != null)
    assertTrue("Debe contener 0AA1 (return_if_false)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AA1") != null)
    assertTrue("Debe contener 0AA3 (free_library)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AA3") != null)
    assertTrue("Debe contener 0AAB (file_exists)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AAB") != null)
    assertTrue("Debe contener 0AB0 (is_key_pressed)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AB0") != null)
    assertTrue("Debe contener 0AB7 (get_vehicle_number_of_gears)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AB7") != null)
    assertTrue("Debe contener 0AD0 (format_string)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AD0") != null)
    assertTrue("Debe contener 0AD8 (write_string_to_file)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AD8") != null)
    assertTrue("Debe contener 0ADC (test_cheat)", com.example.compiler.CleoOpcodeDatabase.findByHex("0ADC") != null)
    assertTrue("Debe contener 0AE4 (directory_exists)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AE4") != null)
    assertTrue("Debe contener 0AE5 (create_directory)", com.example.compiler.CleoOpcodeDatabase.findByHex("0AE5") != null)
  }

  @Test
  fun `compiles script using batch 2 GTA SA opcodes`() {
    val script = """
      03A4: name_thread 'BLOCK2'
      0001: wait 0 ms
      02E7: start_cutscene
      031A: remove_all_fires
      0793: save_player_clothes
      0794: restore_player_clothes
      0828: set_max_fire_generations 5
      04DB: exit_RC_mode
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar script del bloque 2 exitosamente: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertEquals(9, success.opcodesCompiled)
  }

  @Test
  fun `compiles script using batch 3 GTA SA opcodes`() {
    val script = """
      03A4: name_thread 'BLOCK3'
      0001: wait 0 ms
      0094: 0@ = abs -25
      0096: 1@ = abs -100.5
      0913: run_external_script 1
      08B3: set_gang_zone 2 as_only_one_available_for_gangwars
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar script del bloque 3 exitosamente: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertEquals(7, success.opcodesCompiled)
  }

  @Test
  fun `compiles script using batch 4 CLEO opcodes`() {
    val script = """
      03A4: name_thread 'BLOCK4'
      0001: wait 0 ms
      0AA1: return_if_false
      0AB0: key_pressed 112
      0AAB: file_exists 'cleo/test.ini'
      0AE4: directory_exists 'cleo/mods'
      0AE5: create_directory 'cleo/logs'
      004E: end_thread
    """.trimIndent()

    val result = CleoCompiler.compile(script)
    assertTrue("Debe compilar script del bloque 4 exitosamente: $result", result is CompilationResult.Success)
    val success = result as CompilationResult.Success
    assertEquals(8, success.opcodesCompiled)
  }
}
