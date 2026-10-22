/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.ui.screens.gzclp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import org.librefit.db.entity.*
import org.librefit.gzclp.domain.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dayNames = listOf("A1", "B1", "A2", "B2")

@Composable
fun GzclpScreen(navController: NavHostController, viewModel: GzclpViewModel = hiltViewModel()) {
    val program by viewModel.program.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val detailedHistory by viewModel.detailedHistory.collectAsStateWithLifecycle()
    val selected by viewModel.selectedSession.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val retryLabel by viewModel.retryLabel.collectAsStateWithLifecycle()
    val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()
    val replayPreview by viewModel.replayPreview.collectAsStateWithLifecycle()
    val onboardingDraft by viewModel.onboardingDraft.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("Today") }
    var showAppMenu by remember { mutableStateOf(false) }

    replayPreview?.let { preview ->
        AlertDialog(onDismissRequest = viewModel::cancelHistoricalCorrection,
            title = { Text("Review corrected progression") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview.conflicts.isNotEmpty()) preview.conflicts.forEach { Text(it) }
                else if (preview.changes.isEmpty()) Text("The correction does not change today's recommendations.")
                else preview.changes.forEach { change ->
                    val tier = Tier.valueOf(change.tier)
                    val oldScheme = GzclpEngine.prescription(tier, Stage.valueOf(change.oldStage)).label
                    val newScheme = GzclpEngine.prescription(tier, Stage.valueOf(change.newStage)).label
                    Text("${change.exerciseName} · ${change.tier}: ${displayWeight(change.oldGrams, weightUnit(program!!))} → ${displayWeight(change.newGrams, weightUnit(program!!))} ${program!!.unit.lowercase()} · $oldScheme → $newScheme. ${change.reason}")
                }
            } },
            confirmButton = {
                if (preview.canApply) TextButton(onClick = viewModel::applyHistoricalCorrection) { Text("Apply progression") }
                else TextButton(onClick = {
                    viewModel.cancelHistoricalCorrection()
                    viewModel.closeSession()
                    tab = "Settings"
                }) { Text("Review lift settings") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelHistoricalCorrection) { Text("Keep current") } })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (program == null) "Setup" else if (active != null) "Workout" else "GZCLP") },
                actions = {
                    IconButton(onClick = { showAppMenu = true }, modifier = Modifier.semantics {
                        contentDescription = "More options"
                    }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                    DropdownMenu(expanded = showAppMenu, onDismissRequest = { showAppMenu = false }) {
                        if (program != null && active == null && selected == null) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                showAppMenu = false
                                tab = "Settings"
                            })
                        }
                        DropdownMenuItem(text = { Text("LibreFit library") }, onClick = {
                            showAppMenu = false
                            navController.navigate(org.librefit.nav.Route.MainScreen) { launchSingleTop = true }
                        })
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error != null) Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 8.dp, end = 8.dp)) {
                    Text("Action needs attention", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(Modifier.align(Alignment.End)) {
                        if (retryLabel != null) TextButton(onClick = viewModel::retryFailedAction) {
                            Text(retryLabel!!)
                        }
                        TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                    }
                }
            }
            when {
                !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                program == null -> Onboarding(onboardingDraft ?: GzclpOnboardingDraft(),
                    viewModel::saveOnboardingDraft, viewModel::configure)
                selected != null -> SessionSummary(selected!!, program!!, detailedHistory,
                    viewModel::closeSession, viewModel::saveSet,
                    onReviewProgress = { viewModel.previewHistoricalCorrection(selected!!.session.id) })
                active != null -> ActiveWorkout(active!!, program!!, templates, progress, detailedHistory, busy,
                    viewModel::saveSet, viewModel::saveExercise, viewModel::saveSession,
                    viewModel::addWarmup, viewModel::generateWarmups, viewModel::replacePendingWarmups,
                    viewModel::addWorkingSet, viewModel::deleteSet, viewModel::moveSet,
                    viewModel::substituteSession, viewModel::undoSessionSubstitution,
                    viewModel::replaceFutureExercise,
                    viewModel::finish, viewModel::abandon)
                else -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Today", "History", "Progress").forEach { name ->
                            FilterChip(selected = tab == name, onClick = { tab = name },
                                label = { Text(name, maxLines = 1, softWrap = false) })
                        }
                    }
                    when (tab) {
                        "Today" -> Today(program!!, templates, progress, history.count { it.status == "COMPLETED" }, busy, viewModel::start)
                        "History" -> History(history, viewModel::openSession)
                        "Progress" -> Progress(detailedHistory, progress, program!!, viewModel::openSession) { tab = "Today" }
                        else -> ProgramSettings(program!!, templates, progress,
                            viewModel::updateProgram, viewModel::updateProgress,
                            viewModel::updateTemplate, viewModel::removeTemplate,
                            viewModel::confirmT1Reset, viewModel::confirmT2Reset,
                            viewModel::exportBackup, viewModel::exportCsv, viewModel::previewRestore,
                            restorePreview, viewModel::confirmRestore, viewModel::cancelRestore)
                    }
                }
            }
        }
    }
}

private fun displayWeight(grams: Long, unit: WeightUnit): String =
    String.format(Locale.US, "%.3f", GzclpWeights.fromGrams(grams, unit))
        .trimEnd('0').trimEnd('.')

private fun weightUnit(program: GzclpProgramEntity): WeightUnit =
    runCatching { WeightUnit.valueOf(program.unit) }.getOrDefault(WeightUnit.KG)

@Composable
private fun Onboarding(draft: GzclpOnboardingDraft,
    onDraft: (GzclpOnboardingDraft) -> Unit,
    onFinish: (WeightUnit, Int, Map<String, String>, Map<String, String>) -> Unit) {
    var unit by rememberSaveable { mutableStateOf(runCatching { WeightUnit.valueOf(draft.unit) }.getOrDefault(WeightUnit.KG)) }
    var days by rememberSaveable { mutableIntStateOf(draft.days) }
    val lifts = remember { defaultLifts.distinctBy { it.key } }
    var loads by rememberSaveable { mutableStateOf(draft.loads) }
    var increments by rememberSaveable { mutableStateOf(draft.increments) }
    var fiveRmMode by rememberSaveable { mutableStateOf(draft.fiveRm) }
    var advancedIncrements by rememberSaveable { mutableStateOf(draft.advanced) }
    var step by rememberSaveable { mutableIntStateOf(draft.step) }
    LaunchedEffect(unit, days, loads, increments, fiveRmMode, advancedIncrements, step) {
        onDraft(GzclpOnboardingDraft(unit.name, days, loads, increments,
            fiveRmMode, advancedIncrements, step))
    }
    val unitLabel = if (unit == WeightUnit.KG) "kg" else "lb"
    val tier = when (step) { 1 -> "T1"; 2 -> "T2"; else -> "T3" }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Set up your program", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
            Text("Step ${step + 1} of 4", style = MaterialTheme.typography.labelLarge)
            if (step == 0) {
                Text("Weight unit", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(unit == WeightUnit.KG, onClick = { unit = WeightUnit.KG }, label = { Text("Kilograms") })
                    FilterChip(unit == WeightUnit.LB, onClick = { unit = WeightUnit.LB }, label = { Text("Pounds") })
                }
                Text("Planned sessions per week", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(days == 3, onClick = { days = 3 }, label = { Text("3 days") })
                    FilterChip(days == 4, onClick = { days = 4 }, label = { Text("4 days") })
                }
                Text("Workouts follow A1 → B1 → A2 → B2 even when you miss a day. Enter your own starting loads on the next screens.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("$tier starting weights", style = MaterialTheme.typography.headlineSmall)
                Text(when (tier) {
                    "T1" -> "Heavy lifts start at 5×3+. Enter a conservative working weight or a tested 5RM."
                    "T2" -> "Volume lifts start at 3×10. Enter these separately from T1, even for the same lift."
                    else -> "Accessories start at 3×15+, with the last set AMRAP."
                })
                if (tier == "T1") Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use tested 5RM (start at ~85%)", modifier = Modifier.weight(1f))
                    Switch(checked = fiveRmMode, onCheckedChange = { fiveRmMode = it })
                }
                TextButton(onClick = { advancedIncrements = !advancedIncrements }) {
                    Text(if (advancedIncrements) "Hide increments" else "Adjust increments")
                }
                lifts.filter { it.tier == tier }.forEach { lift ->
                    HorizontalDivider()
                    Text(lift.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    NumberField(loads[lift.key].orEmpty(), { loads = loads + (lift.key to it) },
                        if (fiveRmMode && tier == "T1") "Tested 5RM ($unitLabel)"
                        else "Starting weight ($unitLabel)", Modifier.fillMaxWidth())
                    if (advancedIncrements) NumberField(increments[lift.key].orEmpty(),
                        { increments = increments + (lift.key to it) },
                        "Increase each success ($unitLabel) · default ${if (lift.id.contains("Squat") || lift.id.contains("Deadlift")) 5 else 2.5}",
                        Modifier.fillMaxWidth())
                }
                if (tier == "T3") Text("Rest defaults: T1 3 min · T2 2 min · T3 90 sec. Change these in Settings.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Back")
                }
                val valid = step == 0 || lifts.filter { it.tier == tier }
                    .all { loads[it.key]?.toDoubleOrNull()?.let { value -> value > 0 } == true }
                Button(onClick = {
                    if (step < 3) step++
                    else {
                        val converted = loads.toMutableMap()
                        if (fiveRmMode) lifts.filter { it.tier == "T1" }.forEach {
                            converted[it.key] = ((loads[it.key]?.toDoubleOrNull() ?: 0.0) * .85).toString()
                        }
                        onFinish(unit, days, converted, increments)
                    }
                }, enabled = valid, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(if (step == 3) "Start GZCLP" else "Continue")
                }
            }
        }
    }
}

@Composable
private fun Today(program: GzclpProgramEntity, templates: List<GzclpTemplateExerciseEntity>,
    progress: List<GzclpProgressEntity>, completedSessions: Int, busy: Boolean, onStart: () -> Unit) {
    val unit = weightUnit(program)
    val day = program.nextWorkoutIndex.coerceIn(0, 3)
    val exercises = templates.filter { it.workoutIndex == day && it.enabled }.sortedBy { it.position }
    val primary = exercises.firstOrNull()
    val primaryProgress = progress.find { it.progressionKey == primary?.progressionKey }
    val primaryTier = runCatching { Tier.valueOf(primary?.tier ?: "T1") }.getOrDefault(Tier.T1)
    val primaryStage = runCatching { Stage.valueOf(primaryProgress?.stage ?: "FIRST") }.getOrDefault(Stage.FIRST)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("${dayNames[day]} next", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (primary != null) {
                Text("${primary.exerciseName} · ${primary.tier}", style = MaterialTheme.typography.titleLarge)
                Text("${displayWeight(primaryProgress?.recommendedGrams ?: 0, unit)} ${program.unit.lowercase()} · ${GzclpEngine.prescription(primaryTier, primaryStage).label}",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStart, enabled = !busy && exercises.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Start ${dayNames[day]}") }
        }
        item { Text("Today's work", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)) }
        items(exercises, key = { it.id }) { template ->
            val lift = progress.find { it.progressionKey == template.progressionKey }
            val tier = runCatching { Tier.valueOf(template.tier) }.getOrDefault(Tier.T3)
            val stage = runCatching { Stage.valueOf(lift?.stage ?: "FIRST") }.getOrDefault(Stage.FIRST)
            val prescription = GzclpEngine.prescription(tier, stage)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(template.tier, modifier = Modifier.width(42.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(template.exerciseName, style = MaterialTheme.typography.titleMedium)
                    Text("${prescription.label} · ${displayWeight(lift?.recommendedGrams ?: 0, unit)} ${program.unit.lowercase()}",
                        style = MaterialTheme.typography.bodyMedium)
                    if (lift?.pendingReset == true) Text("Reset required before starting", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
        }
        item { Text("Session ${completedSessions + 1} · Rotation continues after missed days.",
            style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveWorkout(details: GzclpSessionWithDetails, program: GzclpProgramEntity,
    templates: List<GzclpTemplateExerciseEntity>, progress: List<GzclpProgressEntity>,
    history: List<GzclpSessionWithDetails>,
    busy: Boolean, onSaveSet: (GzclpSetEntity) -> Unit,
    onSaveExercise: (GzclpSessionExerciseEntity) -> Unit,
    onSaveSession: (GzclpSessionEntity) -> Unit,
    onAddWarmup: (GzclpSessionExerciseEntity) -> Unit,
    onGenerateWarmups: (GzclpSessionExerciseEntity) -> Unit,
    onReplacePendingWarmups: (GzclpSessionExerciseEntity) -> Unit,
    onAddWorkingSet: (GzclpSessionExerciseEntity) -> Unit,
    onDeleteSet: (Long) -> Unit, onMoveSet: (GzclpSetEntity, Int) -> Unit,
    onSubstituteSession: (Long, String, String, Long) -> Unit,
    onUndoSubstitution: (Long) -> Unit,
    onReplaceFuture: (String, String, String, Long) -> Unit,
    onFinish: () -> Unit,
    onAbandon: () -> Unit) {
    FocusedWorkout(details, program, templates, progress, history, busy, onSaveSet,
        onSaveExercise, onSaveSession, onAddWarmup, onGenerateWarmups,
        onReplacePendingWarmups, onAddWorkingSet, onDeleteSet, onMoveSet,
        onSubstituteSession, onUndoSubstitution, onReplaceFuture, onFinish, onAbandon)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusedWorkout(details: GzclpSessionWithDetails, program: GzclpProgramEntity,
    templates: List<GzclpTemplateExerciseEntity>, progress: List<GzclpProgressEntity>,
    history: List<GzclpSessionWithDetails>, busy: Boolean,
    onSaveSet: (GzclpSetEntity) -> Unit,
    onSaveExercise: (GzclpSessionExerciseEntity) -> Unit,
    onSaveSession: (GzclpSessionEntity) -> Unit,
    onAddWarmup: (GzclpSessionExerciseEntity) -> Unit,
    onGenerateWarmups: (GzclpSessionExerciseEntity) -> Unit,
    onReplacePendingWarmups: (GzclpSessionExerciseEntity) -> Unit,
    onAddWorkingSet: (GzclpSessionExerciseEntity) -> Unit,
    onDeleteSet: (Long) -> Unit, onMoveSet: (GzclpSetEntity, Int) -> Unit,
    onSubstituteSession: (Long, String, String, Long) -> Unit,
    onUndoSubstitution: (Long) -> Unit,
    onReplaceFuture: (String, String, String, Long) -> Unit,
    onFinish: () -> Unit, onAbandon: () -> Unit) {
    val unit = weightUnit(program)
    val sorted = details.exercises.sortedBy { it.exercise.position }
    val working = sorted.flatMap { it.sets }.filterNot { it.warmup }
    val completed = working.count { it.completed }
    val unresolved = sorted.firstOrNull { !it.exercise.skipped && it.sets.any { set -> !set.warmup && !set.completed } }
    var chosenId by rememberSaveable { mutableLongStateOf(0L) }
    val current = sorted.firstOrNull { it.exercise.id == chosenId } ?: unresolved ?: sorted.firstOrNull()
    val item = current ?: return
    val ex = item.exercise
    val warmups = item.sets.filter { it.warmup }.sortedBy { it.position }
    val exerciseSets = item.sets.filterNot { it.warmup }.sortedBy { it.position }
    val nextWarmup = if (ex.skipped) null else warmups.firstOrNull { !it.completed }
    val nextWorking = if (ex.skipped) null else exerciseSets.firstOrNull { !it.completed }
    val nextSet = nextWarmup ?: nextWorking
    val allResolved = sorted.all { it.exercise.skipped || it.sets.none { set -> !set.warmup && !set.completed } }
    val prior = history.asSequence().flatMap { it.exercises.asSequence() }
        .filter { it.exercise.exerciseId == ex.exerciseId && it.exercise.tier == ex.tier }
        .mapNotNull { past -> past.sets.filter { it.completed && !it.warmup }.maxByOrNull { it.position } }
        .firstOrNull()
    var tool by remember { mutableStateOf("") }
    var plateTarget by remember { mutableLongStateOf(-1L) }
    var showWarmupReview by remember { mutableStateOf(false) }
    var showFinishReview by remember { mutableStateOf(false) }
    var showAbandon by remember { mutableStateOf(false) }
    var substitution by remember(ex.id) { mutableStateOf("") }
    var substitutionLoad by remember(ex.id) { mutableStateOf("") }
    var substitutionConfirm by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(details.session.restDeadlineMillis, details.session.restPausedMillis) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val timer = RestDeadline(details.session.restDeadlineMillis, details.session.restPausedMillis)
    val remaining = timer.remaining(now)
    val exceptional = sorted.mapNotNull { lift ->
        val state = progress.find { it.progressionKey == lift.exercise.progressionKey } ?: return@mapNotNull null
        if (lift.exercise.skipped) return@mapNotNull null
        val decision = GzclpEngine.evaluate(
            LiftProgress(lift.exercise.exerciseId, Tier.valueOf(lift.exercise.tier),
                Stage.valueOf(lift.exercise.stage), lift.exercise.recommendedGrams,
                state.incrementGrams, state.cycleStartGrams, state.pendingReset),
            lift.sets.map { CompletedSet(it.actualReps, it.actualGrams, it.warmup, it.completed) })
        if (decision.status == DecisionStatus.ADVANCE_STAGE || decision.status == DecisionStatus.RESET_REQUIRED)
            "${lift.exercise.exerciseName}: ${decision.reason}" else null
    }
    fun requestFinish() {
        if (allResolved && exceptional.isEmpty()) onFinish() else showFinishReview = true
    }
    if (showFinishReview) AlertDialog(onDismissRequest = { showFinishReview = false },
        title = { Text(if (allResolved) "Review progression" else "Finish with sets remaining?") },
        text = { Text(buildString {
            if (!allResolved) append("Unfinished attempted exercises may move to a lower rep scheme. Skip an exercise if it was not attempted.\\n\\n")
            if (exceptional.isNotEmpty()) append(exceptional.joinToString("\\n\\n"))
        }) },
        confirmButton = { TextButton(onClick = { showFinishReview = false; onFinish() }) { Text("Finish workout") } },
        dismissButton = { TextButton(onClick = { showFinishReview = false }) { Text("Keep logging") } })
    if (showAbandon) AlertDialog(onDismissRequest = { showAbandon = false },
        title = { Text("Discard workout?") },
        text = { Text("This workout will not change your progression.") },
        confirmButton = { TextButton(onClick = { showAbandon = false; onAbandon() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { showAbandon = false }) { Text("Keep workout") } })
    if (showWarmupReview) AlertDialog(onDismissRequest = { showWarmupReview = false },
        title = { Text("Replace pending warm-ups?") },
        text = { Text("${warmups.count { !it.completed }} pending warm-ups, including your edits, will be replaced. Completed warm-ups stay recorded.") },
        confirmButton = { TextButton(onClick = { showWarmupReview = false; onReplacePendingWarmups(ex) }) { Text("Replace pending") } },
        dismissButton = { TextButton(onClick = { showWarmupReview = false }) { Text("Keep warm-ups") } })
    if (substitutionConfirm.isNotEmpty()) AlertDialog(onDismissRequest = { substitutionConfirm = "" },
        title = { Text(if (substitutionConfirm == "future") "Change future workouts?" else "Substitute this workout?") },
        text = { Text(if (substitutionConfirm == "future")
            "Future sessions will use $substitution with its own starting weight and progression. Today's prescription stays as recorded."
            else "Today's sets will belong to $substitution. The original ${ex.exerciseName} progression will not advance. No completed sets can be relabeled.") },
        confirmButton = { TextButton(onClick = {
            val option = ExerciseAlternatives.suggestions(ex.exerciseId).firstOrNull { it.name == substitution }
            val id = option?.id ?: substitution.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val grams = GzclpWeights.toGrams(substitutionLoad.toDouble(), unit)
            if (substitutionConfirm == "future") onReplaceFuture(ex.templateId, id, substitution.trim(), grams)
            else onSubstituteSession(ex.id, id, substitution.trim(), grams)
            substitutionConfirm = ""; tool = ""
        }) { Text("Confirm change") } },
        dismissButton = { TextButton(onClick = { substitutionConfirm = "" }) { Text("Cancel") } })
    if (plateTarget >= 0L) {
        val inventory = program.plateInventory.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            val grams = parts.getOrNull(0)?.toLongOrNull()
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (grams != null && count != null) Plate(grams, count) else null
        }
        val loading = PlateCalculator.calculate(plateTarget, program.barGrams, inventory)
        ModalBottomSheet(onDismissRequest = { plateTarget = -1 }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Load the bar", style = MaterialTheme.typography.headlineSmall)
                Text("Target ${displayWeight(plateTarget, unit)} ${program.unit.lowercase()} · Bar ${displayWeight(program.barGrams, unit)} ${program.unit.lowercase()}")
                Text("Each side", style = MaterialTheme.typography.titleMedium)
                if (loading.perSide.isEmpty()) Text("Empty bar")
                loading.perSide.forEach { Text("${it.count} × ${displayWeight(it.grams, unit)} ${program.unit.lowercase()}") }
                Text("Actual ${displayWeight(loading.actualGrams, unit)} ${program.unit.lowercase()}")
                if (loading.differenceGrams != 0L) Text("Difference ${displayWeight(loading.differenceGrams, unit)} ${program.unit.lowercase()}")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (tool.isNotEmpty()) ModalBottomSheet(onDismissRequest = { tool = "" }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (tool) { "warmup" -> "Warm-ups"; "substitute" -> "Substitute ${ex.exerciseName}";
                "notes" -> "Notes and effort"; else -> "Exercise options" }, style = MaterialTheme.typography.headlineSmall)
            when (tool) {
                "warmup" -> {
                    Text("Warm-ups do not affect GZCLP progression.")
                    Button(onClick = { tool = ""; if (warmups.isEmpty()) onGenerateWarmups(ex) else showWarmupReview = true }) {
                        Text(if (warmups.isEmpty()) "Generate warm-ups" else "Regenerate pending")
                    }
                    TextButton(onClick = { onAddWarmup(ex); tool = "" }) { Text("Add a warm-up") }
                    warmups.forEachIndexed { index, set ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("W${index + 1} · ${displayWeight(set.actualGrams, unit)} ${program.unit.lowercase()} × ${set.actualReps}${if (set.completed) " · done" else ""}", Modifier.weight(1f))
                            if (!set.completed) TextButton(onClick = { onDeleteSet(set.id) }) { Text("Remove") }
                        }
                    }
                }
                "substitute" -> {
                    Text("Choose a related movement. Its load and progression are independent of ${ex.exerciseName}.")
                    val original = templates.find { it.id == ex.templateId }
                    ExerciseAlternatives.suggestions(ex.exerciseId).forEach { option ->
                        TextButton(onClick = { substitution = option.name }) { Text("${option.name} · ${option.equipment}") }
                    }
                    if (original != null && original.exerciseId != ex.exerciseId &&
                        !ex.progressionKey.startsWith("session:")) {
                        Text("Future sessions currently use ${original.exerciseName}.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            onReplaceFuture(ex.templateId, ex.exerciseId, ex.exerciseName,
                                ex.recommendedGrams)
                            tool = ""
                        }) { Text("Undo future change") }
                    }
                    NumberOrTextField(substitution, { substitution = it }, "Replacement exercise")
                    NumberField(substitutionLoad, { substitutionLoad = it },
                        "Replacement working weight (${program.unit.lowercase()})")
                    val validLoad = substitutionLoad.toDoubleOrNull()?.let { it > 0 } == true
                    val canChangeToday = item.sets.none { it.completed || it.warmup } &&
                        (original == null || (original.exerciseId == ex.exerciseId &&
                            original.progressionKey == ex.progressionKey))
                    if (!canChangeToday) Text(
                        if (original != null && original.exerciseId != ex.exerciseId)
                            "Undo the future change before replacing today's lift."
                        else "Recorded sets stay with their exercise. Remove pending warm-ups or substitute before logging.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        if (ex.tier == "T3") {
                            val option = ExerciseAlternatives.suggestions(ex.exerciseId).firstOrNull { it.name == substitution }
                            val id = option?.id ?: substitution.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                            onSubstituteSession(ex.id, id, substitution.trim(),
                                GzclpWeights.toGrams(substitutionLoad.toDouble(), unit))
                            tool = ""
                        } else substitutionConfirm = "session"
                    }, enabled = substitution.isNotBlank() && validLoad && canChangeToday) {
                        Text("Use for this workout")
                    }
                    if (!ex.progressionKey.startsWith("session:")) TextButton(onClick = {
                        substitutionConfirm = "future"
                    }, enabled = substitution.isNotBlank() && validLoad) { Text("Use in future workouts") }
                    if (ex.progressionKey.startsWith("session:")) {
                        TextButton(onClick = { onUndoSubstitution(ex.id); tool = "" },
                            enabled = item.sets.none { it.completed }) { Text("Undo this workout's substitution") }
                    }
                }
                "notes" -> {
                    NumberOrTextField(ex.cue, { onSaveExercise(ex.copy(cue = it)) }, "Technique cue")
                    NumberOrTextField(ex.notes, { onSaveExercise(ex.copy(notes = it)) }, "Exercise notes")
                    NumberOrTextField(details.session.notes,
                        { onSaveSession(details.session.copy(notes = it)) }, "Workout notes")
                }
                else -> {
                    TextButton(onClick = { tool = "substitute" }) { Text("Substitute exercise") }
                    TextButton(onClick = { onAddWorkingSet(ex); tool = "" }) { Text("Add working set") }
                    TextButton(onClick = { onSaveExercise(ex.copy(skipped = !ex.skipped)); tool = "" }) {
                        Text(if (ex.skipped) "Restore exercise" else "Skip exercise")
                    }
                    TextButton(onClick = { tool = ""; showAbandon = true }) { Text("Discard workout") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("${dayNames[details.session.workoutIndex]} workout",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("$completed of ${working.size} working sets", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (working.isEmpty()) 0f else completed.toFloat() / working.size },
                modifier = Modifier.fillMaxWidth())
        }
        if (details.session.restDeadlineMillis > 0L || details.session.restPausedMillis > 0L) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(if (details.session.restPausedMillis > 0L) "Rest paused" else if (remaining == 0L) "Ready for next set" else
                    "Rest ${remaining / 60000}:${((remaining / 1000) % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (remaining == 0L && details.session.restPausedMillis == 0L) {
                        TextButton(onClick = { onSaveSession(details.session.copy(restDeadlineMillis = 0,
                            restPausedMillis = 0)) }) { Text("Dismiss") }
                    } else {
                        TextButton(onClick = { onSaveSession(details.session.copy(restDeadlineMillis = 0, restPausedMillis = 0)) }) { Text("Skip") }
                        TextButton(onClick = { val changed = timer.adjust(System.currentTimeMillis(), -15_000)
                            onSaveSession(details.session.copy(restDeadlineMillis = changed.deadlineMillis, restPausedMillis = changed.pausedMillis)) }) { Text("−15") }
                        TextButton(onClick = { val changed = timer.adjust(System.currentTimeMillis(), 15_000)
                            onSaveSession(details.session.copy(restDeadlineMillis = changed.deadlineMillis, restPausedMillis = changed.pausedMillis)) }) { Text("+15") }
                        TextButton(onClick = { val changed = if (details.session.restPausedMillis > 0) timer.resume(System.currentTimeMillis()) else timer.pause(System.currentTimeMillis())
                            onSaveSession(details.session.copy(restDeadlineMillis = changed.deadlineMillis, restPausedMillis = changed.pausedMillis)) }) {
                            Text(if (details.session.restPausedMillis > 0) "Resume" else "Pause")
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("${ex.tier} · ${ex.exerciseName}", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("${ex.targetSets}×${ex.targetReps}${if (ex.tier == "T2") "" else "+"} · ${displayWeight(ex.recommendedGrams, unit)} ${program.unit.lowercase()}",
                    style = MaterialTheme.typography.titleLarge)
                if (prior != null) Text("Last: ${displayWeight(prior.actualGrams, unit)} ${program.unit.lowercase()} × ${prior.actualReps}",
                    style = MaterialTheme.typography.bodyMedium)
                if (ex.cue.isNotBlank()) Text(ex.cue, style = MaterialTheme.typography.bodySmall)
            }
            if (nextWarmup != null) item {
                FocusedSetRow(nextWarmup, "Warm-up W${warmups.indexOf(nextWarmup) + 1}", unit, onSaveSet) {}
            } else if (nextWorking != null) item {
                FocusedSetRow(nextWorking, "Working set ${exerciseSets.indexOf(nextWorking) + 1}${if (nextWorking.amrap) " · AMRAP" else ""}",
                    unit, onSaveSet) {
                    val seconds = if (ex.restSeconds > 0) ex.restSeconds else when (ex.tier) {
                        "T1" -> program.t1RestSeconds; "T2" -> program.t2RestSeconds; else -> program.t3RestSeconds }
                    val hasAnother = sorted.any { row -> !row.exercise.skipped &&
                        row.sets.any { set -> !set.warmup && !set.completed && set.id != nextWorking.id } }
                    if (program.autoRest && seconds > 0 && hasAnother)
                        onSaveSession(details.session.copy(restDeadlineMillis = System.currentTimeMillis() + seconds * 1000L,
                            restPausedMillis = 0))
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { tool = "warmup" }) { Text("Warm up", maxLines = 1) }
                    if (nextSet != null && ExerciseAlternatives.equipmentFor(ex.exerciseId) == "Barbell")
                        TextButton(onClick = { plateTarget = nextSet.actualGrams }) { Text("Plates", maxLines = 1) }
                    TextButton(onClick = { tool = "notes" }) { Text("Notes", maxLines = 1) }
                    TextButton(onClick = { tool = "options" }) { Text("More", maxLines = 1) }
                }
                if (ex.tier != "T2") Text("Final set is AMRAP. Stop with 1–2 reps in reserve.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (warmups.isNotEmpty()) item {
                Text("Warm-ups", style = MaterialTheme.typography.titleMedium)
                warmups.forEachIndexed { index, set ->
                    if (set.completed) CompactCompletedSet("W${index + 1}", set, unit) { onSaveSet(set.copy(completed = false)) }
                }
            }
            item {
                Text("Working sets", style = MaterialTheme.typography.titleMedium)
                if (nextWorking == null) Text(if (ex.skipped) "Exercise skipped" else "All working sets recorded.")
                exerciseSets.forEachIndexed { index, set ->
                    if (set.completed) CompactCompletedSet("${index + 1}${if (set.amrap) " · AMRAP" else ""}", set, unit) {
                        onSaveSet(set.copy(completed = false))
                    }
                }
                val latest = exerciseSets.filter { it.completed }.maxByOrNull { it.position }
                if (latest != null) {
                    val past = history.flatMap { it.exercises }
                        .filter { it.exercise.exerciseId == ex.exerciseId }
                        .flatMap { it.sets }.filter { it.completed && !it.warmup }
                        .map { Performance(ex.exerciseId, it.actualGrams, it.actualReps) }
                    if (past.isNotEmpty()) {
                        val records = Records.newRecords(Records.forExercise(past, ex.exerciseId),
                            Performance(ex.exerciseId, latest.actualGrams, latest.actualReps))
                        records.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            item {
                Text("Other exercises", style = MaterialTheme.typography.titleMedium)
                sorted.filter { it.exercise.id != ex.id }.forEach { other ->
                    val done = other.sets.count { it.completed && !it.warmup }
                    val total = other.sets.count { !it.warmup }
                    TextButton(onClick = { chosenId = other.exercise.id }, modifier = Modifier.fillMaxWidth()) {
                        Text("${other.exercise.tier} · ${other.exercise.exerciseName}  $done/$total", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (allResolved) Button(onClick = ::requestFinish, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Finish workout") }
                else {
                    Text("${working.size - completed} working sets left", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = ::requestFinish, enabled = !busy) { Text("Finish early") }
                }
            }
        }
    }
}

@Composable
private fun CompactCompletedSet(label: String, set: GzclpSetEntity, unit: WeightUnit, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label · ${displayWeight(set.actualGrams, unit)} ${unit.name.lowercase()} × ${set.actualReps}",
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onUndo, modifier = Modifier.semantics {
            contentDescription = "Undo $label completed set"
        }) { Text("Undo") }
    }
}

@Composable
private fun FocusedSetRow(set: GzclpSetEntity, label: String, unit: WeightUnit,
    onSave: (GzclpSetEntity) -> Unit, onComplete: () -> Unit) {
    var edit by remember(set.id) { mutableStateOf(set.amrap) }
    var reps by remember(set.id) { mutableStateOf(set.actualReps.toString()) }
    var weight by remember(set.id) { mutableStateOf(displayWeight(set.actualGrams, unit)) }
    var rir by remember(set.id) { mutableStateOf(set.rir) }
    var showRir by remember(set.id) { mutableStateOf(set.rir != null) }
    var saving by remember(set.id) { mutableStateOf(false) }
    LaunchedEffect(set.completed) { if (set.completed) saving = false }
    LaunchedEffect(saving, set.completed) {
        if (saving && !set.completed) {
            delay(2_500)
            saving = false // A failed write must leave the set available for another attempt.
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (set.amrap) Text("AMRAP: stop with 1–2 reps in reserve.", style = MaterialTheme.typography.bodySmall)
        if (edit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(weight, { weight = it }, label = { Text(unit.name.lowercase()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(reps, { reps = it }, label = { Text("reps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            if (showRir) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..4).forEach { value -> FilterChip(selected = rir == value, onClick = { rir = value },
                    label = { Text("$value") }) }
            } else TextButton(onClick = { showRir = true }) { Text("Add RIR") }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${displayWeight(set.actualGrams, unit)} ${unit.name.lowercase()} × ${set.targetReps}",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { edit = true }) { Text("Edit") }
        }
        Button(onClick = {
            if (!saving) {
                saving = true
                onSave(set.copy(completed = true,
                    actualReps = reps.toIntOrNull()?.coerceAtLeast(0) ?: set.targetReps,
                    actualGrams = weight.toDoubleOrNull()?.let { GzclpWeights.toGrams(it, unit) } ?: set.actualGrams,
                    rir = rir))
                onComplete()
            }
        }, enabled = !saving && reps.toIntOrNull() != null && weight.toDoubleOrNull() != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics {
                contentDescription = "Complete $label: $weight ${unit.name.lowercase()} for $reps reps"
            }) { Text("Complete set") }
    }
}

@Composable
private fun SetRow(set: GzclpSetEntity, index: Int, unit: WeightUnit,
    onSave: (GzclpSetEntity) -> Unit, onRemove: (() -> Unit)? = null,
    onComplete: () -> Unit) {
    var reps by remember(set.id) { mutableStateOf((if (set.completed) set.actualReps else set.targetReps).toString()) }
    var weight by remember(set.id) { mutableStateOf(displayWeight(set.actualGrams, unit)) }
    var showRir by remember(set.id) { mutableStateOf(set.rir != null) }
    var rir by remember(set.id) { mutableStateOf(set.rir) }
    val repsFocus = remember(set.id) { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (set.warmup) "Warm-up W${index + 1}" else if (set.amrap) "Working set ${index + 1} · AMRAP"
            else "Working set ${index + 1}", fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = weight, onValueChange = {
                weight = it
                it.toDoubleOrNull()?.let { load -> onSave(set.copy(actualGrams = GzclpWeights.toGrams(load, unit))) }
            }, label = { Text(if (unit == WeightUnit.KG) "kg" else "lb") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { repsFocus.requestFocus() }),
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = reps, onValueChange = {
                reps = it
                it.toIntOrNull()?.let { count -> onSave(set.copy(actualReps = count.coerceAtLeast(0))) }
            }, label = { Text("reps") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f).focusRequester(repsFocus))
            FilledTonalButton(onClick = {
                onSave(set.copy(
                    actualReps = reps.toIntOrNull()?.coerceAtLeast(0) ?: set.targetReps,
                    actualGrams = weight.toDoubleOrNull()?.let { GzclpWeights.toGrams(it, unit) } ?: set.actualGrams,
                    rir = rir,
                    completed = !set.completed
                ))
                if (!set.completed) onComplete()
            }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (set.completed) "✓" else "Done")
            }
        }
        if (!set.warmup) {
            if (showRir) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reps in reserve", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                listOf(0, 1, 2, 3, 4).forEach { value ->
                    FilterChip(selected = rir == value, onClick = { rir = value; onSave(set.copy(rir = value)) },
                        label = { Text("$value") })
                }
            } else TextButton(onClick = { showRir = true }) { Text("+ RIR") }
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove set") }
    }
}

@Composable
private fun SessionSummary(details: GzclpSessionWithDetails, program: GzclpProgramEntity,
    history: List<GzclpSessionWithDetails>,
    onBack: () -> Unit, onSaveSet: (GzclpSetEntity) -> Unit,
    onReviewProgress: () -> Unit) {
    val unit = weightUnit(program)
    val sets = details.exercises.flatMap { it.sets }.filter { it.completed && !it.warmup }
    val volume = sets.sumOf { it.actualGrams * it.actualReps }
    val minutes = (((details.session.finishedAt ?: System.currentTimeMillis()) -
        details.session.startedAt) / 60_000L).coerceAtLeast(0)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Workout ${dayNames[details.session.workoutIndex]} complete", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text("${details.exercises.count { ex -> ex.sets.any { it.completed } }} exercises · $minutes min · ${displayWeight(volume, unit)} ${program.unit.lowercase()} volume",
                style = MaterialTheme.typography.bodySmall)
            Text("Next prescriptions", style = MaterialTheme.typography.titleLarge)
            if (details.session.notes.isNotBlank()) Text(details.session.notes)
            if (details.session.historicalCorrection) Text(
                "This workout was corrected. Preview how it could affect today's recommendations.",
                color = MaterialTheme.colorScheme.tertiary)
        }
        items(details.exercises.sortedBy { it.exercise.position }) { item ->
            val previousBest = history.filter { it.session.startedAt < details.session.startedAt }
                .flatMap { it.exercises }
                .filter { it.exercise.progressionKey == item.exercise.progressionKey }
                .flatMap { it.sets }
                .filter { it.completed && it.amrap && !it.warmup }
                .maxOfOrNull { it.actualReps }
            val currentBest = item.sets.filter { it.completed && it.amrap && !it.warmup }
                .maxOfOrNull { it.actualReps }
            val pastVolumes = history.filter { it.session.startedAt < details.session.startedAt }
                .flatMap { it.exercises }
                .filter { it.exercise.exerciseId == item.exercise.exerciseId }
                .map { past -> past.sets.filter { it.completed && !it.warmup }
                    .sumOf { it.actualGrams * it.actualReps } }
            val currentVolume = item.sets.filter { it.completed && !it.warmup }
                .sumOf { it.actualGrams * it.actualReps }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${item.exercise.tier} · ${item.exercise.exerciseName}", style = MaterialTheme.typography.titleMedium)
                    if (details.session.historicalCorrection) {
                        Text("Progression needs review after this correction.",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (item.exercise.progressionReason.isNotBlank()) {
                        Text("Next: ${item.exercise.nextStage?.let { Stage.valueOf(it) }?.let {
                            GzclpEngine.prescription(Tier.valueOf(item.exercise.tier), it).label
                        } ?: "—"} at ${displayWeight(item.exercise.nextRecommendedGrams ?: item.exercise.recommendedGrams, unit)} ${program.unit.lowercase()}",
                            style = MaterialTheme.typography.titleMedium)
                        Text(item.exercise.progressionReason, style = MaterialTheme.typography.bodySmall)
                    }
                    var correcting by remember(item.exercise.id) { mutableStateOf(false) }
                    var showSets by rememberSaveable(item.exercise.id) { mutableStateOf(false) }
                    TextButton(onClick = { showSets = !showSets }) { Text(if (showSets) "Hide recorded sets" else "View recorded sets") }
                    if (showSets) item.sets.filter { it.completed }.sortedBy { it.position }.forEach { set ->
                        Text("${if (set.warmup) "Warm-up" else if (set.amrap) "AMRAP" else "Set ${set.position + 1}"}: ${set.actualReps} × ${displayWeight(set.actualGrams, unit)} ${program.unit.lowercase()}")
                    }
                    if (showSets) TextButton(onClick = { correcting = !correcting }) { Text(if (correcting) "Close corrections" else "Correct sets") }
                    if (correcting) item.sets.sortedBy { it.position }.forEach { set ->
                        val index = item.sets.filter { it.warmup == set.warmup }
                            .sortedBy { it.position }.indexOfFirst { it.id == set.id }
                        SetRow(set, index, unit, onSaveSet) {}
                    }
                    if (item.exercise.notes.isNotBlank()) Text(item.exercise.notes)
                    if (currentBest != null && previousBest != null && currentBest > previousBest)
                        Text("AMRAP rep PR · $currentBest reps", color = MaterialTheme.colorScheme.primary)
                    if (currentVolume > 0 && pastVolumes.isNotEmpty() && currentVolume > (pastVolumes.maxOrNull() ?: 0L))
                        Text("Session volume PR · ${displayWeight(currentVolume, unit)} ${program.unit.lowercase()}",
                            color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            if (details.session.historicalCorrection) {
                TextButton(onClick = onReviewProgress, modifier = Modifier.fillMaxWidth()) {
                    Text("Preview correction impact")
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun History(sessions: List<GzclpSessionEntity>, onOpen: (Long) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Workout history", style = MaterialTheme.typography.headlineMedium) }
        if (sessions.isEmpty()) item { Text("Your finished workouts will appear here.") }
        items(sessions.sortedByDescending { it.startedAt }, key = { it.id }) { session ->
            ElevatedCard(onClick = { onOpen(session.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${dayNames[session.workoutIndex]} · ${DateFormat.getDateInstance().format(Date(session.startedAt))}",
                        style = MaterialTheme.typography.titleMedium)
                    Text(session.status.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

private data class TrainingPoint(val sessionId: Long, val date: Long, val value: Long)

@Composable
private fun Progress(sessions: List<GzclpSessionWithDetails>, progress: List<GzclpProgressEntity>,
    program: GzclpProgramEntity, onOpen: (Long) -> Unit, onToday: () -> Unit) {
    val allCompleted = sessions.filter { it.session.status == "COMPLETED" }
    if (allCompleted.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Strength progress", style = MaterialTheme.typography.headlineMedium)
            Text("Complete a workout to start a trend. Your actual sets will appear here; starting recommendations are not performance data.")
            Button(onClick = onToday) { Text("Go to Today") }
        }
        return
    }
    var range by rememberSaveable { mutableIntStateOf(90) }
    var name by rememberSaveable { mutableStateOf("Squat") }
    var tier by rememberSaveable { mutableStateOf("T1") }
    var metric by rememberSaveable { mutableStateOf("Weight") }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    val unit = weightUnit(program)
    val recordEvents = Records.history(allCompleted.mapNotNull { session ->
        val exercise = session.exercises.firstOrNull {
            val liftName = defaultLifts.find { default -> default.key == it.exercise.progressionKey }?.name
                ?: it.exercise.exerciseName
            liftName == name && it.exercise.tier == tier
        } ?: return@mapNotNull null
        RecordSession(session.session.id, session.session.startedAt,
            exercise.sets.filter { it.completed && !it.warmup }
                .map { Performance(exercise.exercise.exerciseId, it.actualGrams, it.actualReps) })
    }).asReversed()
    val visibleSessions = allCompleted.filter { range == 0 ||
        it.session.startedAt >= System.currentTimeMillis() - range * 86_400_000L }
    val relevant = visibleSessions.sortedBy { it.session.startedAt }.mapNotNull { session ->
        val exercise = session.exercises.firstOrNull {
            val liftName = defaultLifts.find { default -> default.key == it.exercise.progressionKey }?.name
                ?: it.exercise.exerciseName
            liftName == name && it.exercise.tier == tier
        } ?: return@mapNotNull null
        val sets = exercise.sets.filter { it.completed && !it.warmup }
        if (sets.isEmpty()) return@mapNotNull null
        val value = when (metric) {
            "Weight" -> sets.maxOf { it.actualGrams }
            "Estimated 1RM" -> sets.filter { it.actualReps in 1..10 }
                .maxOfOrNull { (it.actualGrams * (1.0 + it.actualReps / 30.0)).toLong() } ?: return@mapNotNull null
            "Volume" -> sets.sumOf { it.actualGrams * it.actualReps }
            else -> sets.filter { it.amrap }.maxOfOrNull { it.actualReps }?.toLong() ?: return@mapNotNull null
        }
        TrainingPoint(session.session.id, session.session.startedAt, value)
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Strength progress", style = MaterialTheme.typography.headlineMedium)
            Text("${allCompleted.size} completed workouts", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Squat", "Bench Press", "Deadlift", "Overhead Press").forEach { lift ->
                    FilterChip(selected = name == lift, onClick = { name = lift }, label = { Text(lift) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("T1", "T2").forEach { choice ->
                    FilterChip(selected = tier == choice, onClick = { tier = choice }, label = { Text(choice) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(28 to "4 weeks", 90 to "3 months", 0 to "All").forEach { (days, label) ->
                    FilterChip(selected = range == days, onClick = { range = days }, label = { Text(label) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Weight", "Estimated 1RM", "Volume", "AMRAP").forEach { choice ->
                    FilterChip(selected = metric == choice, onClick = { metric = choice }, label = { Text(choice) })
                }
            }
            if (metric == "Estimated 1RM") Text("Estimate: Epley formula for completed sets of 1–10 reps.",
                style = MaterialTheme.typography.bodySmall)
            if (relevant.isEmpty()) Text("No recorded $tier $name $metric in this range.")
            else {
                Text("$name · $tier · $metric", style = MaterialTheme.typography.titleLarge)
                Text("${if (metric == "AMRAP") "reps" else program.unit.lowercase()} · ${DateFormat.getDateInstance().format(Date(relevant.first().date))} to ${DateFormat.getDateInstance().format(Date(relevant.last().date))}",
                    style = MaterialTheme.typography.bodySmall)
                if (relevant.size == 1) Text("One recorded result. Complete another $tier $name workout to see a trend.",
                    style = MaterialTheme.typography.bodyMedium)
                else TrainingTrend(relevant, metric, unit, onOpen)
            }
        }
        items(relevant, key = { it.sessionId }) { point ->
            TextButton(onClick = { onOpen(point.sessionId) }, modifier = Modifier.fillMaxWidth()) {
                val value = if (metric == "AMRAP") "${point.value} reps"
                    else "${displayWeight(point.value, unit)} ${program.unit.lowercase()}"
                Text("${DateFormat.getDateInstance().format(Date(point.date))}  ·  $value",
                    modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            TextButton(onClick = { showRecords = !showRecords }) {
                Text(if (showRecords) "Hide record history" else "Record history (${recordEvents.size})")
            }
        }
        if (showRecords) items(recordEvents.size) { index ->
            val event = recordEvents[index]
            val value = displayWeight(event.grams, unit)
            val label = when (event.kind) {
                RecordKind.BASELINE -> "First recorded performance"
                RecordKind.HEAVIEST -> "Heaviest set · $value ${program.unit.lowercase()}"
                RecordKind.REPS_AT_WEIGHT -> "${event.reps} reps at $value ${program.unit.lowercase()}"
                RecordKind.ESTIMATED_ONE_REP_MAX -> "Estimated 1RM · $value ${program.unit.lowercase()}"
                RecordKind.SESSION_VOLUME -> "Session volume · $value ${program.unit.lowercase()}"
            }
            TextButton(onClick = { onOpen(event.sessionId) }, modifier = Modifier.fillMaxWidth()) {
                Text("${DateFormat.getDateInstance().format(Date(event.date))} · $label",
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TrainingTrend(points: List<TrainingPoint>, metric: String, unit: WeightUnit,
    onOpen: (Long) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val low = points.minOf { it.value }
    val high = points.maxOf { it.value }
    val label: (Long) -> String = { if (metric == "AMRAP") "$it reps" else "${displayWeight(it, unit)} ${unit.name.lowercase()}" }
    Column {
        Text(label(high), style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(220.dp).padding(vertical = 8.dp)
            .pointerInput(points) {
                detectTapGestures { location ->
                    val fraction = (location.x / size.width).coerceIn(0f, 1f)
                    val index = (fraction * (points.size - 1)).roundToInt()
                    onOpen(points[index].sessionId)
                }
            }) {
            val pad = 12.dp.toPx()
            val span = (high - low).coerceAtLeast(1L).toFloat()
            val vertices = points.mapIndexed { index, point ->
                Offset(pad + (size.width - 2 * pad) * (if (points.size == 1) .5f else index.toFloat() / (points.size - 1)),
                    size.height - pad - (size.height - 2 * pad) * (point.value - low) / span)
            }
            vertices.zipWithNext().forEach { (start, end) -> drawLine(color, start, end, strokeWidth = 3.dp.toPx()) }
            vertices.forEach { drawCircle(color, radius = 5.dp.toPx(), center = it) }
        }
        Text(label(low), style = MaterialTheme.typography.labelMedium)
        Text("Tap a point or a dated result to open its workout.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProgramSettings(program: GzclpProgramEntity,
    templates: List<GzclpTemplateExerciseEntity>, progress: List<GzclpProgressEntity>,
    onProgram: (GzclpProgramEntity) -> Unit, onProgress: (GzclpProgressEntity) -> Unit,
    onTemplate: (GzclpTemplateExerciseEntity) -> Unit,
    onRemove: (GzclpTemplateExerciseEntity) -> Unit,
    onT1Reset: (String, Long) -> Unit, onT2Reset: (String, Long) -> Unit,
    onExport: (android.net.Uri) -> Unit, onCsv: (android.net.Uri) -> Unit,
    onImport: (android.net.Uri) -> Unit, restorePreview: String?,
    onConfirmRestore: () -> Unit, onCancelRestore: () -> Unit) {
    val unit = weightUnit(program)
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }
    var newName by remember { mutableStateOf("") }
    var selectedDay by remember { mutableIntStateOf(0) }
    var newTier by remember { mutableStateOf("T3") }
    var group by rememberSaveable { mutableStateOf("Program") }
    val settingsScroll = rememberScrollState()
    LaunchedEffect(group) { settingsScroll.scrollTo(0) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) onExport(it)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        if (it != null) onCsv(it)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) onImport(it)
    }
    if (restorePreview != null) AlertDialog(onDismissRequest = onCancelRestore,
        title = { Text("Restore training data?") }, text = { Text(restorePreview) },
        confirmButton = { TextButton(onClick = onConfirmRestore) { Text("Replace local data") } },
        dismissButton = { TextButton(onClick = onCancelRestore) { Text("Cancel") } })
    Column(Modifier.fillMaxSize().verticalScroll(settingsScroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Program", "Equipment", "Timing", "Data").forEach { section ->
                FilterChip(selected = group == section, onClick = { group = section }, label = { Text(section) })
            }
        }
        if (group == "Data") {
        Text("Data ownership", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { backupLauncher.launch("librefit-training-backup.json") }) { Text("Export complete backup") }
        TextButton(onClick = { csvLauncher.launch("librefit-workouts.csv") }) { Text("Export workout CSV") }
        TextButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Restore backup") }
        }
        if (group == "Program") {
        Text("Training days per week")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 4).forEach { days ->
                FilterChip(selected = program.daysPerWeek == days,
                    onClick = { onProgram(program.copy(daysPerWeek = days)) },
                    label = { Text("$days days") })
            }
        }
        Text("Weight unit")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeightUnit.entries.forEach { choice ->
                FilterChip(selected = unit == choice,
                    onClick = { onProgram(program.copy(unit = choice.name)) },
                    label = { Text(choice.name.lowercase()) })
            }
        }
        }
        if (group == "Timing") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic rest timer", Modifier.weight(1f))
            Switch(checked = program.autoRest, onCheckedChange = { onProgram(program.copy(autoRest = it)) })
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Text(if (notificationsGranted) "Rest notifications are enabled."
                else "The in-app timer works without notification access. Allow notifications for rest controls on the lock screen.",
                style = MaterialTheme.typography.bodySmall)
            if (!notificationsGranted) {
                TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow rest notifications")
                }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("Open Android notification settings") }
            }
        }
        listOf("T1" to program.t1RestSeconds, "T2" to program.t2RestSeconds,
            "T3" to program.t3RestSeconds).forEach { (tier, seconds) ->
            var value by remember(program.id, tier) { mutableStateOf(seconds.toString()) }
            NumberField(value, {
                value = it
                it.toIntOrNull()?.takeIf { number -> number >= 0 }?.let { secondsValue ->
                    onProgram(when (tier) {
                        "T1" -> program.copy(t1RestSeconds = secondsValue)
                        "T2" -> program.copy(t2RestSeconds = secondsValue)
                        else -> program.copy(t3RestSeconds = secondsValue)
                    })
                }
            }, "$tier rest (seconds)")
        }
        }
        if (group == "Equipment") {
        var bar by remember(unit) { mutableStateOf(displayWeight(program.barGrams, unit)) }
        NumberField(bar, {
            bar = it
            it.toDoubleOrNull()?.takeIf { number -> number >= 0 }?.let { number ->
                onProgram(program.copy(barGrams = GzclpWeights.toGrams(number, unit)))
            }
        }, "Bar weight (${program.unit.lowercase()})")
        PlateInventoryEditor(program, unit, onProgram)
        WarmupTemplateEditor(program, onProgram)
        }
        if (group == "Program") {
        var showLifts by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showLifts = !showLifts }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showLifts) "Hide lift progression" else "Manage lift progression",
                modifier = Modifier.fillMaxWidth())
        }
        var expandedLift by rememberSaveable { mutableStateOf<String?>(null) }
        if (showLifts) progress.forEach { lift ->
            val name = defaultLifts.find { it.key == lift.progressionKey }?.name ?: lift.exerciseId
            TextButton(onClick = { expandedLift = if (expandedLift == lift.progressionKey) null else lift.progressionKey },
                modifier = Modifier.fillMaxWidth()) {
                Text("${lift.tier} · $name · ${displayWeight(lift.recommendedGrams, unit)} ${program.unit.lowercase()}",
                    modifier = Modifier.fillMaxWidth())
            }
            if (expandedLift == lift.progressionKey) {
            var weight by remember(lift.progressionKey, unit) { mutableStateOf(displayWeight(lift.recommendedGrams, unit)) }
            var increment by remember(lift.progressionKey, unit) { mutableStateOf(displayWeight(lift.incrementGrams, unit)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(weight, {
                    weight = it
                    it.toDoubleOrNull()?.let { value ->
                        onProgress(lift.copy(recommendedGrams = GzclpWeights.toGrams(value, unit), pendingReset = false))
                    }
                }, "Next weight", Modifier.weight(1f))
                NumberField(increment, {
                    increment = it
                    it.toDoubleOrNull()?.takeIf { value -> value > 0 }?.let { value ->
                        onProgress(lift.copy(incrementGrams = GzclpWeights.toGrams(value, unit)))
                    }
                }, "Increment", Modifier.weight(1f))
            }
            if (lift.pendingReset) {
                Text(if (lift.tier == "T1") "Test a new five-rep max. The app will restart at about 85%."
                    else "Confirm a 3×10 weight above ${displayWeight(lift.cycleStartGrams, unit)} and no more than ${displayWeight(lift.cycleStartGrams + GzclpWeights.toGrams(if (unit == WeightUnit.KG) 9.0 else 20.0, unit), unit)} ${program.unit.lowercase()}.")
                var resetValue by remember(lift.progressionKey) { mutableStateOf("") }
                NumberField(resetValue, { resetValue = it },
                    if (lift.tier == "T1") "Tested 5RM" else "New 3×10 weight")
                val grams = GzclpWeights.toGrams(resetValue.toDoubleOrNull() ?: 0.0, unit)
                val validReset = if (lift.tier == "T1") grams > 0 else
                    grams > lift.cycleStartGrams &&
                        grams <= lift.cycleStartGrams + GzclpWeights.toGrams(if (unit == WeightUnit.KG) 9.0 else 20.0, unit)
                Button(onClick = {
                    if (lift.tier == "T1") onT1Reset(lift.progressionKey, grams)
                    else onT2Reset(lift.progressionKey, grams)
                }, enabled = validReset) { Text("Confirm reset") }
            } else {
                var showReset by remember(lift.progressionKey) { mutableStateOf(false) }
                if (showReset) AlertDialog(onDismissRequest = { showReset = false },
                    title = { Text("Restart $name ${lift.tier}?") },
                    text = { Text("The next prescription returns to the first stage at ${displayWeight(lift.recommendedGrams, unit)} ${program.unit.lowercase()}. Past workouts stay recorded.") },
                    confirmButton = { TextButton(onClick = {
                        showReset = false
                        onProgress(lift.copy(stage = "FIRST", pendingReset = false,
                            cycleStartGrams = lift.recommendedGrams))
                    }) { Text("Restart lift") } },
                    dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } })
                TextButton(onClick = { showReset = true }) { Text("Restart this lift") }
            }
            }
        }
        HorizontalDivider()
        Text("Workout exercises", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dayNames.forEachIndexed { index, name ->
                FilterChip(selectedDay == index, onClick = { selectedDay = index }, label = { Text(name) })
            }
        }
        val dayTemplates = templates.filter { it.workoutIndex == selectedDay }.sortedBy { it.position }
        dayTemplates.forEachIndexed { index, lift ->
            val accessories = dayTemplates.filter { it.tier == "T3" }
            val accessoryIndex = accessories.indexOfFirst { it.id == lift.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${lift.tier} · ${lift.exerciseName}", Modifier.weight(1f))
                if (lift.tier == "T3") {
                    TextButton(onClick = {
                        if (accessoryIndex > 0) {
                            val other = accessories[accessoryIndex - 1]
                            onTemplate(other.copy(position = lift.position))
                            onTemplate(lift.copy(position = other.position))
                        }
                    }, enabled = accessoryIndex > 0) { Text("↑") }
                    TextButton(onClick = {
                        if (accessoryIndex < accessories.lastIndex) {
                            val other = accessories[accessoryIndex + 1]
                            onTemplate(other.copy(position = lift.position))
                            onTemplate(lift.copy(position = other.position))
                        }
                    }, enabled = accessoryIndex < accessories.lastIndex) { Text("↓") }
                }
                TextButton(onClick = { onTemplate(lift.copy(enabled = !lift.enabled)) }) {
                    Text(if (lift.enabled) "Hide" else "Show")
                }
                if (lift.tier != "T1") TextButton(onClick = { onRemove(lift) }) { Text("Remove") }
            }
            var cue by remember(lift.id) { mutableStateOf(lift.cue) }
            NumberOrTextField(cue, { cue = it; onTemplate(lift.copy(cue = it)) }, "Technique cue")
            var rest by remember(lift.id) { mutableStateOf(lift.restSeconds.toString()) }
            NumberField(rest, {
                rest = it
                it.toIntOrNull()?.takeIf { value -> value >= 0 }?.let { value -> onTemplate(lift.copy(restSeconds = value)) }
            }, "Rest override in seconds (0 = tier default)")
            var replacing by remember(lift.id) { mutableStateOf(false) }
            var replacement by remember(lift.id) { mutableStateOf("") }
            TextButton(onClick = { replacing = !replacing }) { Text("Replace exercise") }
            if (replacing) {
                NumberOrTextField(replacement, { replacement = it }, "New exercise name")
                Button(onClick = {
                    val name = replacement.trim()
                    val exerciseId = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    val key = "$exerciseId:${lift.tier}"
                    if (progress.none { it.progressionKey == key }) {
                        val previous = progress.find { it.progressionKey == lift.progressionKey }
                        onProgress(GzclpProgressEntity(
                            progressionKey = key, exerciseId = exerciseId, tier = lift.tier,
                            recommendedGrams = previous?.recommendedGrams ?: 0,
                            incrementGrams = previous?.incrementGrams ?: GzclpWeights.toGrams(2.5, unit)
                        ))
                    }
                    onTemplate(lift.copy(exerciseId = exerciseId, exerciseName = name, progressionKey = key))
                    replacement = ""
                    replacing = false
                }, enabled = replacement.isNotBlank()) { Text("Use this exercise") }
            }
        }
        NumberOrTextField(newName, { newName = it }, "Add exercise name")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("T2", "T3").forEach {
                FilterChip(newTier == it, onClick = { newTier = it }, label = { Text(it) })
            }
        }
        Button(onClick = {
            val clean = newName.trim()
            val key = "${clean.lowercase().replace(' ', '_')}:$newTier"
            val id = "${selectedDay}_${System.currentTimeMillis()}"
            onTemplate(GzclpTemplateExerciseEntity(
                id = id, workoutIndex = selectedDay,
                position = (templates.filter { it.workoutIndex == selectedDay }.maxOfOrNull { it.position } ?: -1) + 1,
                exerciseId = key.substringBefore(':'), exerciseName = clean, tier = newTier,
                progressionKey = key
            ))
            onProgress(GzclpProgressEntity(
                progressionKey = key, exerciseId = key.substringBefore(':'),
                tier = newTier, recommendedGrams = 0, incrementGrams = GzclpWeights.toGrams(2.5, unit)
            ))
            newName = ""
        }, enabled = newName.isNotBlank()) { Text("Add ${newTier} exercise") }
        }
    }
}

@Composable
private fun PlateInventoryEditor(program: GzclpProgramEntity, unit: WeightUnit,
    onProgram: (GzclpProgramEntity) -> Unit) {
    val rows = program.plateInventory.split(',').mapNotNull { entry ->
        val parts = entry.split(':')
        val weight = parts.getOrNull(0)?.toLongOrNull()
        val count = parts.getOrNull(1)?.toIntOrNull()
        if (weight != null && weight > 0 && count != null && count >= 0) weight to count else null
    }
    fun save(next: List<Pair<Long, Int>>) {
        onProgram(program.copy(plateInventory = next.joinToString(",") { "${it.first}:${it.second}" }))
    }
    Text("Available plates", style = MaterialTheme.typography.titleLarge)
    Text("Count is the total number of plates, shared between both sides.", style = MaterialTheme.typography.bodySmall)
    rows.forEachIndexed { index, (grams, count) ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var weightText by remember(index, unit, program.plateInventory) { mutableStateOf(displayWeight(grams, unit)) }
            var countText by remember(index, program.plateInventory) { mutableStateOf(count.toString()) }
            NumberField(weightText, { value ->
                weightText = value
                value.toDoubleOrNull()?.takeIf { it > 0 }?.let { load ->
                    save(rows.toMutableList().also { it[index] = GzclpWeights.toGrams(load, unit) to count })
                }
            }, "Plate ${unit.name.lowercase()}", Modifier.weight(1f))
            NumberField(countText, { value ->
                countText = value
                value.toIntOrNull()?.takeIf { it >= 0 }?.let { number ->
                    save(rows.toMutableList().also { it[index] = grams to number })
                }
            }, "Total", Modifier.weight(1f))
            TextButton(onClick = { save(rows.filterIndexed { row, _ -> row != index }) }) { Text("Remove") }
        }
    }
    TextButton(onClick = { save(rows + (GzclpWeights.toGrams(if (unit == WeightUnit.KG) 1.25 else 2.5, unit) to 2)) }) {
        Text("Add plate size")
    }
}

@Composable
private fun WarmupTemplateEditor(program: GzclpProgramEntity,
    onProgram: (GzclpProgramEntity) -> Unit) {
    val rows = program.warmupTemplate.split(',').mapNotNull { entry ->
        val parts = entry.split(':')
        val percent = parts.getOrNull(0)?.toIntOrNull()
        val reps = parts.getOrNull(1)?.toIntOrNull()
        if (percent != null && percent in 1..99 && reps != null && reps > 0) percent to reps else null
    }
    fun save(next: List<Pair<Int, Int>>) {
        onProgram(program.copy(warmupTemplate = next.joinToString(",") { "${it.first}:${it.second}" }))
    }
    Text("Warm-up steps", style = MaterialTheme.typography.titleLarge)
    Text("The empty bar is added automatically when appropriate.", style = MaterialTheme.typography.bodySmall)
    rows.forEachIndexed { index, (percent, reps) ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var percentText by remember(index, program.warmupTemplate) { mutableStateOf(percent.toString()) }
            var repsText by remember(index, program.warmupTemplate) { mutableStateOf(reps.toString()) }
            NumberField(percentText, { value ->
                percentText = value
                value.toIntOrNull()?.takeIf { it in 1..99 }?.let { number ->
                    save(rows.toMutableList().also { it[index] = number to reps })
                }
            }, "% of target", Modifier.weight(1f))
            NumberField(repsText, { value ->
                repsText = value
                value.toIntOrNull()?.takeIf { it > 0 }?.let { number ->
                    save(rows.toMutableList().also { it[index] = percent to number })
                }
            }, "Reps", Modifier.weight(1f))
            TextButton(onClick = { save(rows.filterIndexed { row, _ -> row != index }) }) { Text("Remove") }
        }
    }
    TextButton(onClick = { save(rows + (80 to 2)) }) { Text("Add warm-up step") }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true, modifier = modifier)
}

@Composable
private fun NumberOrTextField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth())
}
