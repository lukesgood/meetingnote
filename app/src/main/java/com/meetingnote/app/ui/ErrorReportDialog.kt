package com.meetingnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetingnote.app.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 오류 보고 미리보기 → GitHub 이슈 작성 화면 열기.
 * 사용자가 보낼 내용을 직접 확인하도록 전체 보고서를 보여준다.
 */
@Composable
fun ErrorReportDialog(
    crash: ErrorReporter.Crash? = null,
    engineError: String? = null,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<ErrorReporter.Report?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { ErrorReporter.buildReport(crash, engineError) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("오류 보고") },
        text = {
            Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "아래 내용이 GitHub 이슈 작성 화면에 채워집니다. 확인 후 GitHub에서 '제출'을 누르면 접수됩니다 " +
                            "(GitHub 계정 필요).\n" +
                            "• 녹음·녹취록·회의록·회의 제목·용어 사전은 포함되지 않습니다.\n" +
                            "• GitHub 이슈는 누구나 볼 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp),
                ) {
                    val r = report
                    if (r == null) Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    } else SelectionContainer {
                        Text(
                            r.title + "\n\n" + r.body,
                            Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = report != null, onClick = {
                report?.let { ErrorReporter.openGitHub(ctx, it) }
                onDismiss()
            }) { Text("GitHub에서 보고") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
