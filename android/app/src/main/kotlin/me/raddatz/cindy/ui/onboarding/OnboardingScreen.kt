package me.raddatz.cindy.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsGymnastics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.demo.demo
import me.raddatz.cindy.ui.components.BrandButton
import me.raddatz.cindy.ui.components.IconBullet
import me.raddatz.cindy.ui.components.rememberReduceMotion
import me.raddatz.cindy.ui.demo.StickFigureDemo
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.text.pluralNameRes
import me.raddatz.cindy.ui.text.summaryText
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.text.textRes
import me.raddatz.cindy.ui.theme.CindyTheme

private sealed interface Page {
    data object Welcome : Page
    data object Setup : Page
    data class ExercisePage(val exercise: Exercise) : Page
    data object Calibration : Page
}

/**
 * The first-run introduction (iOS `OnboardingView`): what the workout is, that the phone goes down
 * once and nothing leaves it, what each exercise looks like around that phone, and why calibration
 * comes first. From the settings [onCalibrate] is null and the calibration hand-off is left out.
 */
@Composable
fun OnboardingScreen(model: AppModel, onFinish: () -> Unit, onCalibrate: (() -> Unit)?) {
    val plan by model.plan.collectAsStateWithLifecycle()
    val pages = remember(plan.exercises) {
        listOf(Page.Welcome, Page.Setup) + plan.exercises.map { Page.ExercisePage(it) } + Page.Calibration
    }
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val isLast = pagerState.currentPage == pages.lastIndex

    // Leaving the intro any way at all counts as having seen it, like dismissing the iOS cover.
    BackHandler(onBack = onFinish)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        HorizontalPager(pagerState, modifier = Modifier.weight(1f)) { index ->
            when (val page = pages[index]) {
                Page.Welcome -> PageLayout(
                    icon = { BigIcon { Icon(Icons.Filled.LocalFireDepartment, null, it) } },
                    title = stringResource(R.string.onboarding_welcome_title),
                ) {
                    Text(stringResource(R.string.onboarding_welcome_body, plan.summaryText.text(), plan.durationMinutes))
                    Text(stringResource(R.string.onboarding_welcome_score))
                }
                Page.Setup -> PageLayout(
                    icon = { CountingPhoneIcon(reduceMotion) },
                    title = stringResource(R.string.onboarding_setup_title),
                ) {
                    IconBullet(Icons.Outlined.Smartphone, stringResource(R.string.common_phone_under_bar))
                    IconBullet(Icons.Outlined.BackHand, stringResource(R.string.onboarding_setup_stays))
                    IconBullet(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.onboarding_setup_beeps))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            stringResource(R.string.onboarding_setup_privacy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is Page.ExercisePage -> {
                    val demo = page.exercise.demo
                    PageLayout(
                        icon = {
                            // The pager pauses every page that is not on screen.
                            StickFigureDemo(
                                demo,
                                isPaused = pagerState.currentPage != index || reduceMotion,
                                modifier = Modifier.heightIn(max = 220.dp),
                            )
                        },
                        title = stringResource(page.exercise.pluralNameRes),
                    ) {
                        IconBullet(Icons.Outlined.Smartphone, stringResource(demo.placement.textRes))
                        IconBullet(Icons.Outlined.SportsGymnastics, stringResource(demo.keyCue.textRes))
                    }
                }
                Page.Calibration -> PageLayout(
                    icon = { BigIcon { Icon(Icons.Filled.GpsFixed, null, it) } },
                    title = stringResource(R.string.onboarding_calibration_title),
                ) {
                    Text(stringResource(R.string.onboarding_calibration_body))
                    Text(stringResource(R.string.onboarding_calibration_repeat))
                    IconBullet(Icons.Outlined.Checkroom, stringResource(R.string.onboarding_calibration_clothes))
                }
            }
        }

        PageIndicator(count = pages.size, current = pagerState.currentPage)

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BrandButton(
                text = stringResource(
                    when {
                        !isLast -> R.string.common_continue
                        onCalibrate == null -> R.string.common_done
                        else -> R.string.onboarding_start_calibration
                    },
                ),
                onClick = {
                    if (!isLast) {
                        scope.launch {
                            val next = pagerState.currentPage + 1
                            if (reduceMotion) pagerState.scrollToPage(next) else pagerState.animateScrollToPage(next)
                        }
                    } else {
                        onCalibrate?.invoke() ?: onFinish()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val hideSecondary = onCalibrate == null && isLast
            TextButton(
                onClick = onFinish,
                enabled = !hideSecondary,
                modifier = Modifier.alpha(if (hideSecondary) 0f else 1f),
            ) {
                Text(stringResource(if (isLast) R.string.onboarding_not_now else R.string.onboarding_skip))
            }
        }
    }
}

/** Shared page layout: an illustration on top, then title and copy. */
@Composable
private fun PageLayout(icon: @Composable () -> Unit, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun BigIcon(icon: @Composable (Modifier) -> Unit) {
    Box(Modifier.padding(vertical = 24.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides CindyTheme.colors.brand,
        ) {
            icon(Modifier.size(64.dp))
        }
    }
}

/** A phone lying on the floor that keeps counting — Cindy's job, as a picture. */
@Composable
private fun CountingPhoneIcon(reduceMotion: Boolean) {
    var count by remember { mutableIntStateOf(7) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            count = 7
            return@LaunchedEffect
        }
        while (true) {
            count = (System.currentTimeMillis() / 900 % 15 + 1).toInt()
            delay(300)
        }
    }
    val locale = appLocale
    Box(Modifier.height(170.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.Smartphone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(140.dp),
        )
        Text(
            Formats.integer(count, locale),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = CindyTheme.colors.brand,
        )
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    val description = stringResource(R.string.onboarding_page, current + 1, count)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (index == current) CindyTheme.colors.brand else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}
