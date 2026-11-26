package rocks.pkb.plektra.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubReleaseUrlTest {
    private val prefix = "https://github.com/pkb-rocks/plektra/releases/tag/"

    @Test
    fun releaseTagsAreSingleSegmentsAndNormalizationIsIdempotent() {
        val cases = listOf(
            "nightly/v26.10" to "nightly%2Fv26.10",
            "nightly/v26.10/extra" to "nightly%2Fv26.10%2Fextra",
            "nightly%2Fv26.10" to "nightly%2Fv26.10",
            "nightly%2fv26.10/extra" to "nightly%2fv26.10%2Fextra",
            "v26.10-stable_1.~" to "v26.10-stable_1.~",
            "release+test:1@2" to "release%2Btest%3A1%402",
            "nightly/Grüße test" to "nightly%2FGr%C3%BC%C3%9Fe%20test",
            "nightly%252Fv1" to "nightly%252Fv1",
            "v1%broken%" to "v1%25broken%25",
            "v1%20test" to "v1%20test"
        )
        for ((tag, encodedTag) in cases) {
            for (suffix in listOf("", "?next=/nightly/v1&escaped=%2F+plus#assets/1", "#assets/nightly?raw=1")) {
                val expected = prefix + encodedTag + suffix
                assertEquals(tag + suffix, expected, normalizeGitHubReleaseUrl(prefix + tag + suffix))
                assertEquals(expected, normalizeGitHubReleaseUrl(expected))
            }
        }
    }

    @Test
    fun unrelatedUrlsAndReleasesFallbackRemainUnchanged() {
        val urls = listOf(
            "https://github.com/pkb-rocks/plektra/releases",
            "https://github.com/pkb-rocks/plektra/releases?next=/tag/nightly/v1#assets",
            "${prefix}?query=nightly/v1",
            "https://github.com/pkb-rocks/plektra/releases/latest",
            "https://github.com/pkb-rocks/plektra/releases/download/nightly/v1/Plektra.apk",
            "https://example.com/pkb-rocks/plektra/releases/tag/nightly/v1",
            "https://github.com.example.com/pkb-rocks/plektra/releases/tag/nightly/v1",
            "https://github.com@other.example/pkb-rocks/plektra/releases/tag/nightly/v1",
            "https://github.com/pkb-rocks/plektra/issues/155",
            "https://github.com/pkb-rocks/plektra/releases#releases/tag/nightly/v1"
        )
        for (url in urls) assertEquals(url, url, normalizeGitHubReleaseUrl(url))
    }

    @Test
    fun repositoryAndUrlPrefixArePreserved() {
        val url = "https://GitHub.com/Other-Owner/Other-Repo/releases/tag/nightly/v1"
        assertEquals(
            "https://GitHub.com/Other-Owner/Other-Repo/releases/tag/nightly%2Fv1",
            normalizeGitHubReleaseUrl(url)
        )
    }
}
