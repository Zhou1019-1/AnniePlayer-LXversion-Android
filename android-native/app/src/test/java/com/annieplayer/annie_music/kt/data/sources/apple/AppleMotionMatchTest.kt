package com.annieplayer.annie_music.kt.data.sources.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMotionMatchTest {

    private fun searchGroup(key: String, items: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "results" to mapOf(
            key to mapOf("data" to items),
        ),
    )

    private fun albumItem(id: String, name: String, artist: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "albums",
        "attributes" to mapOf("name" to name, "artistName" to artist),
    )

    @Test
    fun candidates_parse_singular_album_group() {
        val raw = searchGroup(
            "album",
            listOf(
                albumItem("536114662", "七里香", "周杰伦"),
                albumItem("535913600", "2004 无与伦比演唱会 (Live)", "周杰伦"),
                mapOf("id" to "", "attributes" to mapOf("name" to "没有 id")),
                mapOf("id" to "1", "attributes" to mapOf("name" to "")),
            ),
        )
        val candidates = appleAlbumCandidates(raw)
        assertEquals(listOf("536114662", "535913600"), candidates.map { it.id })
        assertEquals("七里香", candidates[0].name)
        assertEquals("周杰伦", candidates[0].artist)
    }

    @Test
    fun candidates_accept_plural_group_too() {
        val raw = searchGroup("albums", listOf(albumItem("1", "Recovery", "Eminem")))
        assertEquals(listOf("1"), appleAlbumCandidates(raw).map { it.id })
    }

    @Test
    fun candidates_empty_when_results_missing() {
        assertTrue(appleAlbumCandidates(emptyMap()).isEmpty())
        assertTrue(appleAlbumCandidates(mapOf("results" to "nope")).isEmpty())
    }

    @Test
    fun exact_match_requires_identical_title_and_artist() {
        val candidate = AppleAlbumCandidate("1", "Ruby", "JENNIE")
        assertTrue(appleAlbumExactMatch("JENNIE", "Ruby", candidate))
        assertTrue(appleAlbumExactMatch(" jennie ", " RUBY ", candidate))
        assertTrue(!appleAlbumExactMatch("제니", "Ruby", candidate))
        assertTrue(!appleAlbumExactMatch("JENNIE", "Ruby (Deluxe Edition)", candidate))
        assertTrue(!appleAlbumExactMatch("JENNIE", "Ruby (The Complete Collection)", candidate))
    }

    @Test
    fun exact_match_ignores_artist_when_source_has_none() {
        val candidate = AppleAlbumCandidate("1", "Ruby", "JENNIE")
        assertTrue(appleAlbumExactMatch("", "Ruby", candidate))
        assertTrue(!appleAlbumExactMatch("", "Ruby Deluxe", candidate))
    }

    @Test
    fun exact_match_survives_multi_artist_spacing_differences_only() {
        val candidate = AppleAlbumCandidate("1", "恒星不忘 Forever Forever", "周杰伦,  言承旭")
        assertTrue(!appleAlbumExactMatch("周杰伦", "恒星不忘 Forever Forever", candidate))
    }

    @Test
    fun tyla_standard_edition_wins_over_deluxe() {
        val raw = searchGroup(
            "album",
            listOf(
                albumItem("1772394609", "TYLA +", "Tyla"),
                albumItem("1812463456", "Bliss - Single", "Tyla"),
                albumItem("1699082722", "Water - Single", "Tyla"),
                albumItem("1717680174", "TYLA", "Tyla"),
            ),
        )
        val candidates = appleAlbumCandidates(raw)
        val exact = candidates.filter { appleAlbumExactMatch("Tyla", "Tyla", it) }
        assertEquals(listOf("1717680174"), exact.map { it.id })
        val relaxed = candidates
            .filter { candidate -> exact.none { it.id == candidate.id } }
            .mapIndexed { index, candidate -> candidate to appleAlbumMatchScore("Tyla", "Tyla", candidate, index) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        assertEquals("1772394609", relaxed.first().id)
    }

    @Test
    fun tyla_deluxe_source_matches_deluxe_album_exactly() {
        val candidate = AppleAlbumCandidate("1772394609", "TYLA +", "Tyla")
        assertTrue(appleAlbumExactMatch("Tyla", "TYLA +", candidate))
        assertTrue(!appleAlbumExactMatch("Tyla", "TYLA", candidate))
    }

    @Test
    fun artist_payload_without_motion_still_yields_still_cover() {
        val raw = mapOf(
            "data" to listOf(
                mapOf(
                    "id" to "1552925732",
                    "attributes" to mapOf(
                        "name" to "Tyla",
                        "artwork" to mapOf(
                            "url" to "https://is1-ssl.mzstatic.com/image/thumb/tyla/{w}x{h}bb.jpg",
                            "width" to 800,
                            "height" to 800,
                        ),
                        "editorialArtwork" to mapOf(
                            "musicContentColorLogoTrimmed" to mapOf("url" to "https://x/{w}x{h}.png"),
                        ),
                    ),
                ),
            ),
        )
        val motion = appleArtistMotionOf(raw)
        assertTrue(motion != null)
        assertEquals("", motion?.motionCover)
        assertTrue(motion?.stillCover?.endsWith("1200x1200bb.jpg") == true)
    }

    @Test
    fun artist_payload_with_motion_prefers_square_variant() {
        val raw = mapOf(
            "data" to listOf(
                mapOf(
                    "id" to "300117743",
                    "attributes" to mapOf(
                        "name" to "周杰伦",
                        "artwork" to mapOf("url" to "https://is1-ssl.mzstatic.com/image/thumb/jay/{w}x{h}bb.jpg"),
                        "editorialVideo" to mapOf(
                            "motionArtistWide16x9" to mapOf("video" to "https://mvod/wide.m3u8"),
                            "motionArtistSquare1x1" to mapOf("video" to "https://mvod/square.m3u8"),
                        ),
                    ),
                ),
            ),
        )
        val motion = appleArtistMotionOf(raw)
        assertEquals("https://mvod/square.m3u8", motion?.motionCover)
        assertTrue(motion?.stillCover?.isNotEmpty() == true)
    }

    @Test
    fun artist_payload_without_artwork_or_motion_is_null() {
        val raw = mapOf(
            "data" to listOf(
                mapOf("id" to "1", "attributes" to mapOf("name" to "无名")),
            ),
        )
        assertTrue(appleArtistMotionOf(raw) == null)
        assertTrue(appleArtistMotionOf(emptyMap()) == null)
    }

    @Test
    fun artist_candidates_parse_singular_group() {
        val raw = mapOf(
            "results" to mapOf(
                "artist" to mapOf(
                    "data" to listOf(
                        mapOf("id" to "1552925732", "attributes" to mapOf("name" to "Tyla")),
                        mapOf("id" to "1126555281", "attributes" to mapOf("name" to "TYLA")),
                        mapOf("id" to "", "attributes" to mapOf("name" to "no id")),
                        mapOf("id" to "1", "attributes" to mapOf("name" to "")),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("1552925732", "1126555281"),
            appleArtistCandidates(raw).map { it.id },
        )
    }

    @Test
    fun artist_exact_match_is_case_and_space_insensitive() {
        val tyla = AppleArtistCandidate("1552925732", "Tyla")
        assertTrue(appleArtistExactMatch("Tyla", tyla))
        assertTrue(appleArtistExactMatch(" tyla ", tyla))
        assertTrue(appleArtistExactMatch("TYLA", tyla))
        assertTrue(!appleArtistExactMatch("Tyla Jane", tyla))
        assertTrue(!appleArtistExactMatch("", tyla))
    }

    @Test
    fun artist_relaxed_match_allows_containment() {
        val jay = AppleArtistCandidate("300117743", "周杰伦")
        assertTrue(appleArtistNameMatches("周杰伦", jay))
        assertTrue(appleArtistNameMatches("周杰伦 Jay", jay))
        assertTrue(!appleArtistNameMatches("Tyla", jay))
    }

    @Test
    fun exact_title_and_artist_scores_highest() {
        val target = AppleAlbumCandidate("1", "七里香", "周杰伦")
        val other = AppleAlbumCandidate("2", "七里香 (Deluxe Edition)", "周杰伦")
        val exact = appleAlbumMatchScore("周杰伦", "七里香", target)
        val deluxe = appleAlbumMatchScore("周杰伦", "七里香", other)
        assertTrue(exact > deluxe)
        assertEquals(133, exact)
        assertEquals(128, deluxe)
    }

    @Test
    fun deluxe_and_single_suffixes_still_match() {
        assertEquals(
            128,
            appleAlbumMatchScore(
                "周杰伦",
                "七里香",
                AppleAlbumCandidate("1", "七里香（豪华版）", "周杰伦"),
            ),
        )
        assertEquals(
            98,
            appleAlbumMatchScore(
                "Eminem",
                "Venom",
                AppleAlbumCandidate("1", "Venom (Music from the Motion Picture) - Single", "Eminem"),
            ),
        )
    }

    @Test
    fun multi_artist_candidate_matches_contained_artist() {
        val score = appleAlbumMatchScore(
            "周杰伦",
            "恒星不忘 Forever Forever",
            AppleAlbumCandidate("1", "恒星不忘 Forever Forever - Single", "周杰伦, 言承旭, 吴建豪, 周渝民 & 五月天 阿信"),
        )
        assertEquals(120, score)
    }

    @Test
    fun artist_mismatch_no_longer_rejects() {
        val mismatch = appleAlbumMatchScore(
            "제니",
            "Ruby",
            AppleAlbumCandidate("1", "Ruby", "JENNIE"),
        )
        val matched = appleAlbumMatchScore(
            "JENNIE",
            "Ruby",
            AppleAlbumCandidate("1", "Ruby", "JENNIE"),
        )
        assertTrue(mismatch > 0)
        assertTrue(matched > mismatch)
        assertEquals(113, mismatch)
    }

    @Test
    fun earlier_search_rank_wins_ties() {
        val first = appleAlbumMatchScore(
            "JENNIE",
            "Ruby",
            AppleAlbumCandidate("1", "Ruby", "JENNIE"),
            rank = 0,
        )
        val second = appleAlbumMatchScore(
            "JENNIE",
            "Ruby",
            AppleAlbumCandidate("2", "Ruby", "JENNIE"),
            rank = 1,
        )
        assertTrue(first > second)
    }

    @Test
    fun title_mismatch_is_rejected() {
        assertEquals(
            0,
            appleAlbumMatchScore("周杰伦", "七里香", AppleAlbumCandidate("1", "叶惠美", "周杰伦")),
        )
    }

    @Test
    fun empty_artist_falls_back_to_title_only() {
        val score = appleAlbumMatchScore("", "七里香", AppleAlbumCandidate("1", "七里香", "周杰伦"))
        assertEquals(113, score)
    }

    @Test
    fun empty_inputs_score_zero() {
        assertEquals(0, appleAlbumMatchScore("周杰伦", "", AppleAlbumCandidate("1", "七里香", "周杰伦")))
        assertEquals(0, appleAlbumMatchScore("周杰伦", "七里香", AppleAlbumCandidate("1", "", "周杰伦")))
    }

    @Test
    fun normalization_keeps_non_edition_brackets() {
        assertEquals("venommusicfromthemotionpicture", appleNormalizeAlbumKey("Venom (Music from the Motion Picture)"))
    }

    @Test
    fun normalization_strips_edition_brackets() {
        assertEquals("recovery", appleNormalizeAlbumKey("Recovery (Deluxe Edition)"))
        assertEquals("recovery", appleNormalizeAlbumKey("Recovery - Expanded"))
    }

    @Test
    fun cache_key_normalization_collapses_separators() {
        assertEquals("周杰伦 七里香", appleNormalizeKeyword("  周杰伦 / 七里香 \n"))
    }
}
