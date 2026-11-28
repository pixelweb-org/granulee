package io.granulee

import io.granulee.recommendation.Dismissed
import io.granulee.recommendation.DismissedStorage
import io.granulee.scan.HistoryPoint
import io.granulee.scan.HistoryStorage
import io.granulee.scan.Snapshot
import io.granulee.scan.SnapshotStorage
import io.granulee.source.SourceRecord
import io.granulee.source.SourceStorage
import io.granulee.trial.Trial
import io.granulee.trial.TrialStorage
import io.granulee.user.Account
import io.granulee.user.AccountAuthentication
import io.granulee.user.Role
import io.granulee.user.Session
import io.granulee.user.SessionStorage
import io.granulee.user.User
import io.granulee.user.UserStorage
import org.springframework.security.core.context.SecurityContextHolder

// Stand-ins for the storage, for tests of what sits on top of it. An instance kept across two objects under test
// is a restart. The real one has its own test in the sqlite package.

class InMemorySourceStorage : SourceStorage {
    private val records = LinkedHashMap<String, SourceRecord>()
    override fun all() = records.values.toList()
    override fun save(record: SourceRecord) { records[record.id] = record }
    override fun remove(id: String) { records.remove(id) }
}

class InMemorySnapshotStorage : SnapshotStorage {
    private val snapshots = LinkedHashMap<String, Snapshot>()
    override fun all() = snapshots.values.toList()
    override fun save(snapshot: Snapshot) { snapshots[snapshot.sourceId] = snapshot }
    override fun forget(sourceId: String) { snapshots.remove(sourceId) }
}

class InMemoryHistoryStorage : HistoryStorage {
    private var points = listOf<HistoryPoint>()
    override fun all() = points
    override fun put(point: HistoryPoint) { points = points.filter { it.day != point.day || it.sourceId != point.sourceId } + point }
    override fun removeBefore(day: String) { points = points.filter { it.day >= day } }
    override fun forget(sourceId: String) { points = points.filter { it.sourceId != sourceId } }
}

class InMemoryDismissedStorage : DismissedStorage {
    private var dismissed = listOf<Dismissed>()
    override fun all() = dismissed
    override fun add(dismissed: Dismissed) { this.dismissed = (this.dismissed + dismissed).distinct() }
    override fun clear(sourceId: String, database: String, table: String) {
        dismissed = dismissed.filter { !(it.sourceId == sourceId && it.database == database && it.table == table) }
    }
    override fun forget(sourceId: String) { dismissed = dismissed.filter { it.sourceId != sourceId } }
}

class InMemoryTrialStorage : TrialStorage {
    private val trials = LinkedHashMap<String, Trial>()
    override fun all() = trials.values.toList()
    override fun save(trial: Trial) { trials[trial.id] = trial }
    override fun remove(id: String) { trials.remove(id) }
}

class InMemoryUserStorage : UserStorage {
    private val users = LinkedHashMap<String, User>()
    override fun all() = users.values.toList()
    override fun find(id: String) = users[id]
    override fun findByName(username: String) = users.values.firstOrNull { it.username.equals(username, ignoreCase = true) }
    override fun count() = users.size
    override fun add(user: User) { users[user.id] = user }
    override fun update(user: User) { users[user.id] = user }
}

class InMemorySessionStorage : SessionStorage {
    val sessions = LinkedHashMap<String, Session>()
    override fun find(tokenHash: String) = sessions[tokenHash]
    override fun add(session: Session) { sessions[session.tokenHash] = session }
    override fun touch(tokenHash: String, lastSeenAt: Long) { sessions[tokenHash]?.let { sessions[tokenHash] = it.copy(lastSeenAt = lastSeenAt) } }
    override fun remove(tokenHash: String) { sessions.remove(tokenHash) }
    override fun removeOf(userId: String, exceptTokenHash: String?) { sessions.values.removeIf { it.userId == userId && it.tokenHash != exceptTokenHash } }
    override fun removeExpired(idleBefore: Long, createdBefore: Long) { sessions.values.removeIf { it.lastSeenAt < idleBefore || it.createdAt < createdBefore } }
}

/** Who the code under test takes for the signed-in user, as a request's thread would have it. */
object SignedIn {
    fun account(id: String, role: String = Role.USER) = Account(id, id, role, mustChangePassword = false, sessionHash = "session-of-$id")

    fun be(id: String) {
        SecurityContextHolder.getContext().authentication = AccountAuthentication(account(id))
    }

    fun nobody() = SecurityContextHolder.clearContext()

    fun <T> `as`(id: String, block: () -> T): T {
        val before = SecurityContextHolder.getContext().authentication
        be(id)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = before
        }
    }
}
