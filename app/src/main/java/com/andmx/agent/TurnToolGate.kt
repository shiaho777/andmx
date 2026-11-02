package com.andmx.agent

object TurnToolGate {
    val AUTOMATION_MUTATION = setOf("CronCreate", "CronUpdate", "CronDelete")
    val OFF_PEAK_MUTATION = setOf("OffPeakCreate", "SendMessage", "Workflow")

    fun hidden(automation: Boolean, offPeak: Boolean): Set<String> = buildSet {
        if (automation) addAll(AUTOMATION_MUTATION)
        if (offPeak) addAll(OFF_PEAK_MUTATION)
    }

    fun denial(toolName: String): String = if (toolName in AUTOMATION_MUTATION) {
        "$toolName is not allowed while running a scheduled automation."
    } else {
        "$toolName is not allowed while running an off-peak task."
    }
}
