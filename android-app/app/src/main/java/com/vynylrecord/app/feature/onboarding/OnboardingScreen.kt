package com.vynylrecord.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vynylrecord.app.LocalVynylGraph
import com.vynylrecord.app.R
import com.vynylrecord.app.core.design.VinylDiscPreview
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylPrimaryButton
import com.vynylrecord.app.core.design.VynylTextButton
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.model.VinylStyleId
import kotlinx.coroutines.launch

/**
 * Three pages, shown once, then never again.
 *
 * The copy is fixed and short, because onboarding that has to be read is onboarding that has been failed by
 * the app. Each page has one idea: this presses your voice onto a record, it happens on this phone, and it
 * is yours to keep. The promise about privacy is here rather than buried in Settings because it is the
 * reason to trust the app with a recording of someone's voice.
 *
 * "Skip" is on every page and does the same thing as "Start": nobody should have to swipe through three
 * screens to reach the record button.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val graph = LocalVynylGraph.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { PAGES.size })

    val finish = {
        scope.launch {
            graph.preferences.setOnboardingComplete(true)
            onFinished()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VynylColors.Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            VynylTextButton(text = stringResource(R.string.onboarding_skip), onClick = { finish() })
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val content = PAGES[page]
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Each page carries a record, drawn with the same canvas the library uses, in the finish that
                // page is about. It is decoration, but it is decoration made of the product.
                VinylDiscPreview(
                    style = content.style,
                    title = content.title,
                    size = 190.dp,
                    spinning = false,
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(content.titleRes),
                    style = VynylType.display,
                    color = VynylColors.Cream,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(content.bodyRes),
                    style = VynylType.bodyLarge,
                    color = VynylColors.Muted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (page in PAGES.indices) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (page == pagerState.currentPage) 10.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (page == pagerState.currentPage) VynylColors.Amber else VynylColors.Muted),
                )
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            val isLast = pagerState.currentPage == PAGES.lastIndex
            VynylPrimaryButton(
                text = stringResource(if (isLast) R.string.onboarding_finish else R.string.onboarding_continue),
                onClick = {
                    if (isLast) {
                        finish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One page's copy and the finish it shows. */
private data class Page(
    val titleRes: Int,
    val bodyRes: Int,
    val title: String,
    val style: VinylStyleId,
)

private val PAGES = listOf(
    Page(
        titleRes = R.string.onboarding_1_title,
        bodyRes = R.string.onboarding_1_body,
        title = "V",
        style = VinylStyleId.DEFAULT,
    ),
    Page(
        titleRes = R.string.onboarding_2_title,
        bodyRes = R.string.onboarding_2_body,
        title = "I",
        style = VinylStyleId.MIDNIGHT_SAPPHIRE,
    ),
    Page(
        titleRes = R.string.onboarding_3_title,
        bodyRes = R.string.onboarding_3_body,
        title = "L",
        style = VinylStyleId.SMOKED_OBSIDIAN,
    ),
)
