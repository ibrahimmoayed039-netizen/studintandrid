package com.attendance.nfc.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
data class User(
    @PrimaryKey val id: String,
    val username: String,
    val name: String,
    val role: String,           // admin | teacher
    val salt: String,
    val hash: String,
    val createdAt: Long,
    /** بطاقات المدرّسين (UID) لتأكيد الحذف بدل كلمة المرور؛ عدة بطاقات مفصولة بفاصلة لمستخدم واحد. فارغة = لا بطاقة. */
    val cardUid: String = "",
    /** يسمح للمدرّس بإضافة طلابه وبطاقاتهم (لا يرى ولا يعدّل إلا من أضافهم). */
    val canStudents: Boolean = false
)

@Entity(tableName = "students")
data class Student(
    @PrimaryKey val id: String,
    val name: String,
    val grade: String = "",
    val cardUid: String = "",
    val photoPath: String = "",
    /** معرّف المستخدم الذي أضاف الطالب (فارغ = أضافه المدير أو الحاسوب). */
    val createdBy: String = ""
)

/** حصة في الجدول الأسبوعي. day: 0 = الأحد ... 6 = السبت. لكل مستخدم جدوله (ownerId). */
@Entity(tableName = "schedule")
data class ScheduleItem(
    @PrimaryKey val id: String,
    val day: Int,
    val subject: String,
    val grp: String = "",
    val startTime: String,      // HH:mm
    val endTime: String = "",   // HH:mm (اختياري)
    val lateMin: Int = 10,
    val auto: Boolean = true,
    val ownerId: String = ""
)

@Entity(tableName = "sessions")
data class LectureSession(
    @PrimaryKey val id: String,
    val subject: String,
    val grp: String = "",
    val lateMin: Int = 10,
    val date: String,           // yyyy-MM-dd
    val startTime: String,      // HH:mm
    val startTs: Long,
    val endTs: Long = 0,
    val active: Boolean = true,
    val autoEnd: Boolean = false,
    val scheduleId: String = "",
    val createdBy: String = "",
    val createdByName: String = ""
)

@Entity(tableName = "attendance", indices = [Index("sessionId"), Index("date")])
data class Attendance(
    @PrimaryKey val id: String,
    val sessionId: String,
    val studentId: String,
    val studentName: String,
    val grade: String,
    val subject: String,
    val date: String,
    val time: String,
    val status: String,         // present | late
    val manual: Boolean = false
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY createdAt")
    fun observeAll(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE LOWER(username) = LOWER(:u) LIMIT 1")
    suspend fun byUsername(u: String): User?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): User?

    @Query("SELECT * FROM users WHERE cardUid != '' AND :uid != '' AND (',' || cardUid || ',') LIKE '%,' || :uid || ',%' LIMIT 1")
    suspend fun byCard(uid: String): User?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE role = 'admin'")
    suspend fun adminCount(): Int

    @Query("SELECT * FROM users")
    suspend fun all(): List<User>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(u: User)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<User>)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM users")
    suspend fun clear()
}

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY name")
    fun observeAll(): Flow<List<Student>>

    @Query("SELECT * FROM students")
    suspend fun all(): List<Student>

    @Query("SELECT * FROM students WHERE cardUid = :uid AND cardUid != '' LIMIT 1")
    suspend fun byCard(uid: String): Student?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: Student)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<Student>)

    @Query("DELETE FROM students WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM students")
    suspend fun clear()
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule WHERE ownerId = :owner ORDER BY day, startTime")
    fun observeByOwner(owner: String): Flow<List<ScheduleItem>>

    @Query("SELECT * FROM schedule")
    suspend fun all(): List<ScheduleItem>

    @Query("DELETE FROM schedule WHERE ownerId = :owner")
    suspend fun deleteByOwner(owner: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: ScheduleItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<ScheduleItem>)

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM schedule")
    suspend fun clear()
}

@Dao
interface SessionDao {
    /** المحاضرة النشطة: للمدير أي محاضرة (تُفضَّل محاضرته)، وللمدرّس محاضرته فقط. */
    @Query("SELECT * FROM sessions WHERE active = 1 AND (:all = 1 OR createdBy = :uid) ORDER BY CASE WHEN createdBy = :uid THEN 0 ELSE 1 END, startTs DESC LIMIT 1")
    fun observeActiveFor(uid: String, all: Int): Flow<LectureSession?>

    @Query("SELECT * FROM sessions WHERE active = 1 AND (:all = 1 OR createdBy = :uid) ORDER BY CASE WHEN createdBy = :uid THEN 0 ELSE 1 END, startTs DESC LIMIT 1")
    suspend fun getActiveFor(uid: String, all: Int): LectureSession?

    @Query("SELECT * FROM sessions WHERE active = 1 AND createdBy = :uid LIMIT 1")
    suspend fun getMine(uid: String): LectureSession?

    @Query("SELECT * FROM sessions ORDER BY startTs DESC")
    fun observeAll(): Flow<List<LectureSession>>

    @Query("SELECT * FROM sessions")
    suspend fun all(): List<LectureSession>

    @Query("SELECT COUNT(*) FROM sessions WHERE scheduleId = :sid AND date = :date")
    suspend fun countFor(sid: String, date: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: LectureSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<LectureSession>)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance WHERE sessionId = :sid ORDER BY time DESC")
    fun observeForSession(sid: String): Flow<List<Attendance>>

    @Query("SELECT * FROM attendance WHERE sessionId = :sid ORDER BY time DESC")
    suspend fun forSession(sid: String): List<Attendance>

    @Query("SELECT * FROM attendance WHERE date >= :from AND date <= :to")
    suspend fun between(from: String, to: String): List<Attendance>

    @Query("SELECT * FROM attendance WHERE date = :date ORDER BY time DESC")
    fun observeByDate(date: String): Flow<List<Attendance>>

    @Query("SELECT * FROM attendance WHERE date LIKE :prefix || '%' ORDER BY date, time")
    fun observeByMonth(prefix: String): Flow<List<Attendance>>

    @Query("SELECT * FROM attendance")
    suspend fun all(): List<Attendance>

    @Query("SELECT * FROM attendance WHERE sessionId = :sid AND studentId = :stid LIMIT 1")
    suspend fun find(sid: String, stid: String): Attendance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: Attendance)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<Attendance>)

    @Query("DELETE FROM attendance WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM attendance")
    suspend fun clear()
}

/** ترحيل من الإصدار 1: إضافة المستخدمين وربط الجدول والمحاضرات بمالكها (المدير الافتراضي). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `username` TEXT NOT NULL, `name` TEXT NOT NULL, `role` TEXT NOT NULL, `salt` TEXT NOT NULL, `hash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("ALTER TABLE schedule ADD COLUMN ownerId TEXT NOT NULL DEFAULT 'admin'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN createdBy TEXT NOT NULL DEFAULT 'admin'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN createdByName TEXT NOT NULL DEFAULT 'المدير'")
    }
}

/** ترحيل من الإصدار 2: إضافة بطاقة المدرّس. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN cardUid TEXT NOT NULL DEFAULT ''")
    }
}

/** ترحيل من الإصدار 3: صلاحية المدرّس لإضافة الطلاب + صاحب الطالب. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN canStudents INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE students ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [User::class, Student::class, ScheduleItem::class, LectureSession::class, Attendance::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun studentDao(): StudentDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun sessionDao(): SessionDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "attendance.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
