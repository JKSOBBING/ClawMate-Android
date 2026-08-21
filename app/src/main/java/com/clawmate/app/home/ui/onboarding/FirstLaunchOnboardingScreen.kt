package com.clawmate.app.home.ui.onboarding

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

private val ONBOARDING_BACKGROUND: Color = Color(0xFFF4F8FF)
private val ONBOARDING_TEXT_PRIMARY: Color = Color(0xFF101B3D)
private val ONBOARDING_TEXT_SECONDARY: Color = Color(0xFF768197)
private val ONBOARDING_CARD_BACKGROUND: Color = Color(0xFFFFFFFF)
private val ONBOARDING_DOT_INACTIVE: Color = Color(0xFFDCE3F0)

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
    FIRST_LAUNCH_ONBOARDING_SLIDE_DATA_HOME -> R.drawable.onboarding_data_home
    FIRST_LAUNCH_ONBOARDING_SLIDE_AVATAR -> R.drawable.onboarding_avatar
    FIRST_LAUNCH_ONBOARDING_SLIDE_AGENT -> R.drawable.onboarding_agent
    else -> null
}

/**
 * Real first-launch onboarding (Compose), ported from
 * `pages/onboarding/FirstLaunchOnboarding.ets`. It renders the three capability
 * preview slides plus a usage-guide page, with 下一页 / skip / 进入首页 actions and
 * progress dots.
 *
 * This composable only renders the flow; the shell decides whether to show it
 * and [OnboardingViewModel] persists the completion version on finish.
 */
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
    val slide = remember(slideIndex) { FIRST_LAUNCH_ONBOARDING_SLIDES[slideIndex] }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ONBOARDING_BACKGROUND),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 30.dp, end = 30.dp, top = 28.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ClawMate",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = ONBOARDING_TEXT_PRIMARY,
            )
            Text(
                text = active?.counterLabel ?: "",
                fontSize = 15.sp,
                color = ONBOARDING_TEXT_SECONDARY,
            )
        }

        SlidePage(
            slide = slide,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            FIRST_LAUNCH_ONBOARDING_SLIDES.forEachIndexed { i, s ->
                val selected = i == slideIndex
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = if (selected) 24.dp else 8.dp, height = 8.dp)
                        .background(
                            color = if (selected) onboardingColor(s.accentColor) else ONBOARDING_DOT_INACTIVE,
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
            }
        }

        // Skip hint (only before the last page).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (!(active?.isLast ?: true)) {
                Text(
                    text = "跳过",
                    fontSize = 14.sp,
                    color = ONBOARDING_TEXT_SECONDARY,
                    modifier = Modifier.clickable { viewModel.skip() },
                )
            }
        }

        Button(
            onClick = { viewModel.next() },
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = onboardingColor(slide.accentColor)),
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .padding(bottom = 34.dp)
                .height(56.dp),
        ) {
            Text(
                text = active?.buttonText ?: "下一页",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
        modifier = modifier.padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(10.dp)
                .background(ONBOARDING_CARD_BACKGROUND, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .background(
                        onboardingColor(slide.softBackgroundColor).copy(alpha = 0.78f),
                        RoundedCornerShape(74.dp),
                    ),
            )
            val heroDrawable = onboardingHeroDrawable(slide.mediaResourceName)
            if (heroDrawable != null) {
                Image(
                    painter = painterResource(id = heroDrawable),
                    contentDescription = null,
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
                .background(onboardingColor(item.softBackgroundColor), RoundedCornerShape(14.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.iconText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = onboardingColor(item.accentColor),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                color = onboardingColor(item.accentColor),
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
