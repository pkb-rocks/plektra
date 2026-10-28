package rocks.pkb.plektra

import org.junit.Assert.*
import org.junit.Test

class ThemePhotoImportRequestTest {
    private val image=KeyboardThemeTexture("texture-${"a".repeat(64)}.png",wholeKeyboard=true)
    private fun theme()=KeyboardThemePreset("Original",1,2,3,4,5,6,7,8,9)

    @Test fun equalFunctionReferencesStillApplyToTheirOwnEditor() {
        val applied=mutableListOf<Int>()
        fun callback(target:Int):(KeyboardThemePreset)->Unit {
            fun apply(theme:KeyboardThemePreset){assertEquals(image,theme.textures["background"]);applied.add(target)}
            return ::apply
        }
        val hardware=callback(0);val software=callback(1)
        // This equality is exactly why rememberUpdatedState retained the previous target.
        assertEquals(hardware,software)
        val original=theme()
        assertTrue(ThemePhotoImportRequest(original,hardware).complete(original,image))
        assertTrue(ThemePhotoImportRequest(original,software).complete(original,image))
        assertEquals(listOf(0,1),applied)
    }
    @Test fun laterColourEditsAreNotOverwrittenByACompletedImport() {
        val original=theme();var applied=false
        val request=ThemePhotoImportRequest(original){applied=true}
        assertFalse(request.complete(original.copy(background=99),image));assertFalse(applied)
    }
    @Test fun addingAnImageKeepsSolidColoursAndOtherRoles() {
        val original=theme().copy(textures=mapOf("normal_key" to image))
        var result:KeyboardThemePreset?=null
        assertTrue(ThemePhotoImportRequest(original){result=it}.complete(original,image))
        assertEquals(original.copy(textures=original.textures+("background" to image)),result)
        assertEquals(original.normalKey,result!!.normalKey)
        assertEquals(original.specialKey,result!!.specialKey)
    }
}
