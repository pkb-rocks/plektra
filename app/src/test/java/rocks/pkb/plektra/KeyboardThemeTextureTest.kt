package rocks.pkb.plektra

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardThemeTextureTest {
    private val asset = "texture-${"a".repeat(64)}.png"
    private val source = "source-${"b".repeat(64)}.png"
    @Test fun preservesExistingTextureMetadataAcrossCodec() {
        val image = KeyboardThemeTexture(asset, "tile", Color.WHITE, true, true, true, .6f,
            Color.BLUE, 2f, source, listOf(.1f,.2f,.8f,.9f), true)
        assertEquals(mapOf("background" to image), ThemeTextureModel.decode(ThemeTextureModel.encode(mapOf("background" to image))))
    }
    @Test fun malformedPathsUnknownRolesAndInvalidModesAreIgnored() {
        val json = JSONObject().put("background", JSONObject().put("asset","../outside.png"))
            .put("unknown", JSONObject().put("asset",asset))
            .put("normal_key", JSONObject().put("asset",asset).put("mode","stretch"))
        assertTrue(ThemeTextureModel.decode(json).isEmpty())
    }
    @Test fun badCropAndOutOfRangeMetadataUseSafeDefaults() {
        val json = JSONObject().put("background",JSONObject().put("asset",asset).put("intensity",5)
            .put("foreground", 999999999999L).put("sourceAsset","../source.png")
            .put("sourceCrop",JSONArray(listOf(0,0,0,1))))
        val image = ThemeTextureModel.decode(json).getValue("background")
        assertEquals(1f,image.intensity,0f);assertNull(image.foreground);assertNull(image.sourceAsset);assertNull(image.sourceCrop)
    }
    @Test fun allConversionsKeepImagesAndStoredSolidColours() {
        val original=keyboardThemePresets().first().copy(textures=mapOf("background" to KeyboardThemeTexture(asset,wholeKeyboard=true)))
        val settings=original.toSettingsTheme()
        val restored=settings.toKeyboardThemePreset(original.name)
        assertEquals(original,restored)
        assertEquals(original.normalKey,settings.normalKey)
        assertEquals(original.specialKey,settings.specialKey)
        assertEquals(Color.TRANSPARENT,settings.toKeyboardThemeColors().normalKey)
        assertEquals(Color.TRANSPARENT,original.toAospThemeOverride().specialKey)
        assertEquals(original.textures,original.toKeyboardThemeColors().textures)
        val removed=original.copy(textures=emptyMap())
        assertEquals(original.normalKey,removed.toKeyboardThemeColors().normalKey)
        assertEquals(original.specialKey,removed.toAospThemeOverride().specialKey)
    }
    @Test fun savedThemeJsonIncludesImagesButClipboardJsonExportsOnlyColours() {
        val preset=keyboardThemePresets().first().copy(textures=mapOf("background" to KeyboardThemeTexture(asset)))
        val saved=SettingsManager.keyboardThemeFromJsonString(SettingsManager.keyboardThemeToJsonString(preset.toSettingsTheme()))!!
        assertEquals(preset.textures,saved.textures)
        val copied=SettingsManager.keyboardThemeFromJsonString(keyboardThemeClipboardJson(preset))!!
        assertTrue(copied.textures.isEmpty());assertEquals(saved.normalKey,copied.normalKey)
        assertEquals(saved.keyHeightScale,copied.keyHeightScale,0f)
    }
    @Test fun backgroundOnlyImageDoesNotHideKeyColoursUnlessWholeKeyboardRequested() {
        val theme=keyboardThemePresets().first().copy(textures=mapOf("background" to KeyboardThemeTexture(asset)))
        assertEquals(theme.normalKey,theme.toKeyboardThemeColors().normalKey)
    }
    @Test fun referencesIncludeSourcesInsideSavedThemesAndDrafts() {
        val theme=JSONObject().put("textures",ThemeTextureModel.encode(mapOf("background" to KeyboardThemeTexture(asset,sourceAsset=source))))
        val library=JSONArray().put(JSONObject().put("theme",theme))
        assertEquals(setOf(asset,source),ThemeTextureModel.referencedAssets(listOf(library.toString(),"fixed")))
    }
    @Test fun geometryDefaultsRemainUnchangedWithAndWithoutImages() {
        val original=keyboardThemePresets().first().withSoftwareKeyboardDefaults()
        val photo=original.copy(textures=mapOf("background" to KeyboardThemeTexture(asset,wholeKeyboard=true)))
        assertEquals(original.copy(textures=photo.textures),photo.withSoftwareKeyboardDefaults())
        assertEquals(original.toSettingsTheme().copy(textures=photo.textures),photo.toSettingsTheme())
    }
    @Test fun nestingLimitIsCheckedBeforeParsingAndQuotedBracketsDoNotCount() {
        assertThrows(IllegalArgumentException::class.java) { ThemeTextureModel.referencedAssets(listOf("[".repeat(40)+"0"+"]".repeat(40))) }
        assertTrue(ThemeTextureModel.referencedAssets(listOf(JSONObject().put("name","[".repeat(80)).toString())).isEmpty())
    }
    @Test fun invalidInMemoryCropIsNotWrittenAsInvalidJson() {
        val value=ThemeTextureModel.encode(mapOf("background" to KeyboardThemeTexture(asset,sourceCrop=listOf(0f,0f,Float.NaN,1f))))
        assertFalse(value.getJSONObject("background").has("sourceCrop"))
    }

}
