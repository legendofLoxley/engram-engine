package app.alfrd.engram.api

import app.alfrd.engram.director.goals.GoalRunnerStartup
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET /debug/goal-runner` — whether the runner exists, whether it starts cycles on its own, what is running and the last report.
 * `POST /debug/goal-runner/run-once` — the single-shot trigger: runs one cycle now and returns its report. Body (optional): `{"goalUid": "<uid>"}`. It waives the idle
 * wait (a person is asking) and nothing else: the kill switch, the memory guard, the slots, the budget and the goal's state all still apply, and it works when
 * `GOAL_RUNNER_ENABLED` is off. Loopback only, debug token, same gate as every debug route.
 */
fun Application.configureDebugGoalRunnerRoutes(startup: GoalRunnerStartup, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
    routing {
        authenticate("debug-token") {
            route("/debug/goal-runner") {
                get {
                    if (!isLoopback(call)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    when (startup) {
                        is GoalRunnerStartup.Disabled -> call.respond(HttpStatusCode.OK, mapOf("built" to "false", "reason" to startup.reason))
                        is GoalRunnerStartup.Ready -> call.respond(
                            HttpStatusCode.OK,
                            mapOf("built" to "true", "enabled" to startup.runner.config.enabled.toString(), "runningGoal" to (startup.runner.runningGoalUid() ?: ""), "last" to (startup.runner.lastReport()?.let { Json.encodeToString(app.alfrd.engram.director.goals.RunReport.serializer(), it) } ?: "")),
                        )
                    }
                }
                post("/run-once") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val runner = (startup as? GoalRunnerStartup.Ready)?.runner ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "the goal runner is not built"))
                    val raw = call.receiveText()
                    val goalUid = if (raw.isBlank()) null else ((runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.get("goalUid") as? JsonPrimitive)?.content
                    call.respond(HttpStatusCode.OK, runner.runOnce(manual = true, goalUid = goalUid))
                }
            }
        }
    }
}
