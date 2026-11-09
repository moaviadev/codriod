package dev.moavia.codriod

class SessionLifecycle {
    enum class State { CREATED, RUNNING, FINISHED, TERMINATED }

    var state: State = State.CREATED
        private set
    var lastColumns: Int = 0
        private set
    var lastRows: Int = 0
        private set

    fun start() {
        check(state == State.CREATED || state == State.FINISHED)
        state = State.RUNNING
    }

    fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0) { "Terminal dimensions must be positive" }
        check(state == State.RUNNING) { "A running session is required for resize" }
        lastColumns = columns
        lastRows = rows
    }

    fun finish() {
        check(state == State.RUNNING) { "Only a running session can finish" }
        state = State.FINISHED
    }

    fun terminate() {
        if (state != State.TERMINATED) state = State.TERMINATED
    }
}
