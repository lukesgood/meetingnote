package com.meetingnote.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TermCorrectorTest {
    private fun fix(text: String, vararg terms: String) =
        TermCorrector.correct(listOf(TranscriptLine(0, 0, text)), terms.toList()).lines[0].text

    @Test fun misrecognizedTerm() {
        assertEquals("데이터브릭스 팀에 물어보죠", fix("데이터브리스트 팀에 물어보죠", "데이터브릭스"))
        assertEquals("여기에는 데이터브릭스 쪽으로", fix("여기에는 테이터브리스 쪽으로", "데이터브릭스"))
        assertEquals("아까 말씀드린 삼성리서치 대응", fix("아까 말씀드린 삼촌리처치 대응", "삼성리서치"))
    }

    @Test fun keepsParticles() {
        assertEquals("오늘 김민수는 나갑니다.", fix("오늘 김민숙은 나갑니다.", "김민수"))
        assertEquals("데이터브릭스나 고객한테", fix("데이터브리스트나 고객한테", "데이터브릭스"))
        assertEquals("박지영을 불러", fix("박지형을 불러", "박지영"))
        assertEquals("김민수가 했어요", fix("김민숙이 했어요", "김민수"))
    }

    @Test fun spacingAndCase() {
        assertEquals("그 삼성카드 관련해서", fix("그 삼성 카드 관련해서", "삼성카드"))
        assertEquals("우리 MSP 계약", fix("우리 msp 계약", "MSP"))
    }

    @Test fun leavesCorrectWordsAlone() {
        assertEquals("경쟁력인 거지", fix("경쟁력인 거지", "경쟁력"))
        assertEquals("본부장님께 보고", fix("본부장님께 보고", "본부장"))
        assertEquals("데이터브릭스만 쓰면", fix("데이터브릭스만 쓰면", "데이터브릭스"))
        assertEquals("그 메가존 빌링", fix("그 메가존 빌링", "메가존"))
    }

    @Test fun rejectsPrefixOnlyMatches() {
        assertEquals("스페이스 클라우드 아닌가 걔는", fix("스페이스 클라우드 아닌가 걔는", "클라우드프론트"))
        assertEquals("클라우드로 할 수 있는", fix("클라우드로 할 수 있는", "클라우드프론트"))
    }

    @Test fun twoLetterTermsOnlySpacing() {
        assertEquals("한사무쪽 일은", fix("한사무쪽 일은", "상무"))
        assertEquals("사무실에서", fix("사무실에서", "상무"))
    }

    /** 실제 회의 녹취록으로 확인 (저장소에 넣지 않음): TERM_FIX_TRANSCRIPT=경로 TERM_FIX_TERMS='용어1, 용어2' */
    @Test fun realTranscript() {
        val path = System.getenv("TERM_FIX_TRANSCRIPT") ?: return
        val arr = JSONArray(File(path).readText())
        val lines = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> TranscriptLine(o.getLong("s"), o.getLong("e"), o.getString("t")) } }
        val r = TermCorrector.correct(lines, TermCorrector.parseTerms(System.getenv("TERM_FIX_TERMS") ?: ""))
        r.fixes.forEach { println("FIX ${it.from} -> ${it.to}") }
        println("FIXES ${r.fixes.size}")
    }
}
