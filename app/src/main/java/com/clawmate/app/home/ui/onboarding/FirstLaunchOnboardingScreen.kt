package com.clawmate.app.home.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.snapshotFlow
import com.clawmate.app.ui.HarmonyButton
import com.clawmate.app.ui.theme.HarmonyTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.R
import com.clawmate.app.home.data.OnboardingRepository
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_SLIDE_AGENT
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_SLIDE_AVATAR
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_SLIDE_DATA_HOME
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_SLIDES
import com.clawmate.app.home.domain.FIRST_LAUNCH_USAGE_GUIDE_ITEMS
import com.clawmate.app.home.domain.FirstLaunchOnboardingSlide
import com.clawmate.app.home.domain.FirstLaunchOnboardingSlideKind
import com.clawmate.app.home.domain.FirstLaunchUsageGuideItem

private val ONBOARDING_BACKGROUND @Composable get() = HarmonyTheme.background("#F4F8FF")
private val ONBOARDING_TEXT_PRIMARY @Composable get() = HarmonyTheme.text("#101B3D")
private val ONBOARDING_TEXT_SECONDARY @Composable get() = HarmonyTheme.text("#768197")
private val ONBOARDING_CARD_BACKGROUND @Composable get() = HarmonyTheme.background("#FFFFFF")
private val ONBOARDING_DOT_INACTIVE @Composable get() = HarmonyTheme.background("#DCE3F0")

/** Parse a `#RRGGBB`/`#AARRGGBB` string into a Compose color. */
fun onboardingColor(hex: String): Color =
    runCatching { Color(parseColorStrict(hex)) }.getOrDefault(Color(0xFF8066FF))

private fun parseColorStrict(hex: String): Long {
    val h = hex.removePrefix("#")
    val v = h.toLong(16)
    return if (h.length == 6) (0xFF000000 or v) else v
}

/** Map a slide's [FirstLaunchOnboardingSlide.mediaResourceName] to its bundled hero illustration. */
private fun onboardingHeroDrawable(mediaResourceName: String?): Int? = when (mediaResourceName) {
    "onboarding_data_home" -> R.drawable.onboarding_data_home
    "onboarding_avatar" -> R.drawable.onboarding_avatar
    "onboarding_agent" -> R.drawable.onboarding_agent
    else -> null
}

/**
 * Real first-launch onboarding (Compose), ported from
 * `pages/onboarding/FirstLaunchOnboarding.ets`. It renders the three capability
 * preview slides plus a usage-guide page, with 下一页 / 进入首页 actions and
 * progress dots.
 *
 * This composable only renders the flow; the shell decides whether to show it
 * and [OnboardingViewModel] persists the completion version on finish.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FirstLaunchOnboardingScreen(
    repository: OnboardingRepository,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModelFactory(repository)),
) {
    val state by viewModel.state.collectAsState()
    val completed by viewModel.completed.collectAsState()

    LaunchedEffect(completed) {
        if (completed) onFinished()
    }

    val active = state as? OnboardingUiState.Active
    val slideIndex = active?.index ?: 0
    val slide = FIRST_LAUNCH_ONBOARDING_SLIDES[slideIndex]
    val pager = rememberPagerState(initialPage = slideIndex, pageCount = { FIRST_LAUNCH_ONBOARDING_SLIDES.size })
    LaunchedEffect(slideIndex) { if (pager.currentPage != slideIndex) pager.animateScrollToPage(slideIndex) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { viewModel.setIndex(it) } }
    Column(Modifier.fillMaxSize().background(ONBOARDING_BACKGROUND).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ClawMate", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ONBOARDING_TEXT_PRIMARY)
            Text(active?.counterLabel.orEmpty(), fontSize = 15.sp, color = ONBOARDING_TEXT_SECONDARY)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("onboarding-pager"), key = { FIRST_LAUNCH_ONBOARDING_SLIDES[it].id }) { index ->
            SlidePage(FIRST_LAUNCH_ONBOARDING_SLIDES[index], Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FIRST_LAUNCH_ONBOARDING_SLIDES.forEachIndexed { index, item ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (index == slideIndex) 24.dp else 8.dp, 8.dp)
                    .background(if (index == slideIndex) HarmonyTheme.background(item.accentColor) else ONBOARDING_DOT_INACTIVE, RoundedCornerShape(4.dp)))
            }
        }
        HarmonyButton(active?.buttonText ?: "下一页", { viewModel.next() }, Modifier.padding(top = 26.dp, bottom = 34.dp).fillMaxWidth(.84f),
            height = 56.dp, radius = 18.dp, fontSize = 18.sp, bold = true, color = HarmonyTheme.text("#FFFFFF"),
            background = HarmonyTheme.fill(slide.accentColor))
    }
}

@Composable
private fun SlidePage(slide: FirstLaunchOnboardingSlide, modifier: Modifier = Modifier) {
    if (slide.kind == FirstLaunchOnboardingSlideKind.USAGE_GUIDE) {
        UsageGuidePage(slide, modifier)
    } else {
        PreviewSlidePage(slide, modifier)
    }
}

@Composable
private fun PreviewSlidePage(slide: FirstLaunchOnboardingSlide, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 30.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(ONBOARDING_CARD_BACKGROUND, RoundedCornerShape(28.dp))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .background(
                        HarmonyTheme.background(slide.softBackgroundColor).copy(alpha = 0.78f),
                        RoundedCornerShape(74.dp),
                    ),
            )
            val heroDrawable = onboardingHeroDrawable(slide.mediaResourceName)
            if (heroDrawable != null) {
                Image(
                    painter = painterResource(id = heroDrawable),
                    contentDescription = "${slide.title}功能预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            text = slide.title,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = ONBOARDING_TEXT_PRIMARY,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 30.dp),
        )

        Text(
            text = slide.description,
            fontSize = 18.sp,
            lineHeight = 28.sp,
            color = ONBOARDING_TEXT_SECONDARY,
            textAlign = TextAlign.Center,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        )
    }
}

@Composable
private fun UsageGuidePage(slide: FirstLaunchOnboardingSlide, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text = slide.title,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = ONBOARDING_TEXT_PRIMARY,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = slide.description,
            fontSize = 17.sp,
            lineHeight = 25.sp,
            color = ONBOARDING_TEXT_SECONDARY,
            textAlign = TextAlign.Center,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FIRST_LAUNCH_USAGE_GUIDE_ITEMS.forEach { item ->
                UsageGuideCard(item)
            }
        }
    }
}

@Composable
private fun UsageGuideCard(item: FirstLaunchUsageGuideItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ONBOARDING_CARD_BACKGROUND, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .size(42.dp)
                .background(HarmonyTheme.background(item.softBackgroundColor), RoundedCornerShape(14.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.iconText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = HarmonyTheme.text(item.accentColor),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = item.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ONBOARDING_TEXT_PRIMARY,
                maxLines = 1,
            )
            Text(
                text = item.entryLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = HarmonyTheme.text(item.accentColor),
                maxLines = 1,
            )
            Text(
                text = item.configLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = ONBOARDING_TEXT_SECONDARY,
                maxLines = 2,
            )
            Text(
                text = item.description,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = ONBOARDING_TEXT_SECONDARY,
                maxLines = 2,
            )
        }
    }
}
