/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.domain

data class ExerciseOption(val id: String, val name: String, val equipment: String, val primaryMuscle: String)

/** Suggestions share a movement pattern; they never inherit another exercise's progression. */
object ExerciseAlternatives {
    private val catalog = listOf(
        ExerciseOption("Barbell_Full_Squat", "Barbell Squat", "Barbell", "Quadriceps"),
        ExerciseOption("Goblet_Squat", "Goblet Squat", "Dumbbell", "Quadriceps"),
        ExerciseOption("Leg_Press", "Leg Press", "Machine", "Quadriceps"),
        ExerciseOption("Barbell_Bench_Press_-_Medium_Grip", "Barbell Bench Press", "Barbell", "Chest"),
        ExerciseOption("Dumbbell_Bench_Press", "Dumbbell Bench Press", "Dumbbell", "Chest"),
        ExerciseOption("Chest_Press", "Chest Press", "Machine", "Chest"),
        ExerciseOption("Barbell_Deadlift", "Barbell Deadlift", "Barbell", "Posterior chain"),
        ExerciseOption("Romanian_Deadlift", "Romanian Deadlift", "Barbell", "Posterior chain"),
        ExerciseOption("Dumbbell_Romanian_Deadlift", "Dumbbell Romanian Deadlift", "Dumbbell", "Posterior chain"),
        ExerciseOption("Standing_Military_Press", "Overhead Press", "Barbell", "Shoulders"),
        ExerciseOption("Dumbbell_Shoulder_Press", "Dumbbell Shoulder Press", "Dumbbell", "Shoulders"),
        ExerciseOption("Shoulder_Press_Machine", "Shoulder Press Machine", "Machine", "Shoulders"),
        ExerciseOption("Wide-Grip_Lat_Pulldown", "Lat Pulldown", "Cable", "Back"),
        ExerciseOption("Pull-Up", "Pull-up", "Bodyweight", "Back"),
        ExerciseOption("Assisted_Pull-Up", "Assisted Pull-up", "Machine", "Back"),
        ExerciseOption("One-Arm_Dumbbell_Row", "Dumbbell Row", "Dumbbell", "Back"),
        ExerciseOption("Seated_Cable_Row", "Seated Cable Row", "Cable", "Back")
    )

    fun suggestions(exerciseId: String, equipment: String? = null): List<ExerciseOption> {
        val original = catalog.find { it.id == exerciseId } ?: return emptyList()
        return catalog.filter { it.id != original.id && it.primaryMuscle == original.primaryMuscle &&
            (equipment == null || it.equipment == equipment) }
    }

    fun equipmentFor(exerciseId: String): String? = catalog.find { it.id == exerciseId }?.equipment
}
