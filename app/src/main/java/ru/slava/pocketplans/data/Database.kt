package ru.slava.pocketplans.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class Product(@PrimaryKey val id: Int, val title: String, val description: String,
    val category: String, val priceCents: Long, val updatedAt: Long)
@Entity(tableName = "personal", foreignKeys = [ForeignKey(entity = Product::class,
    parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)])
data class Personal(@PrimaryKey val productId: Int, val note: String = "",
    val targetCents: Long? = null, val favorite: Boolean = false)
@Entity(tableName = "plans")
data class Plan(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val budgetCents: Long, val createdAt: Long)
@Entity(tableName = "entries", primaryKeys = ["planId", "productId"], indices = [Index("productId")], foreignKeys = [
    ForeignKey(entity = Plan::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)])
data class PlanEntry(val planId: Long, val productId: Int, val quantity: Int = 1, val purchased: Boolean = false)
@Entity(tableName = "history", foreignKeys = [ForeignKey(entity = Product::class,
    parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)])
data class Visit(@PrimaryKey val productId: Int, val seenAt: Long)
@Entity(tableName = "presets")
data class Preset(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val query: String, val category: String, val favoritesOnly: Boolean)
@Entity(tableName = "sync_meta")
data class SyncMeta(@PrimaryKey val id: Int = 1, val successAt: Long)
@Entity(tableName = "price_points", primaryKeys = ["productId", "observedAt"], foreignKeys = [
    ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)])
data class PricePoint(val productId: Int, val observedAt: Long, val priceCents: Long)

@Dao
interface PocketDao {
    @Query("SELECT * FROM products ORDER BY title") fun products(): Flow<List<Product>>
    @Query("SELECT * FROM products WHERE id=:id") suspend fun getProduct(id: Int): Product?
    @Query("SELECT * FROM products") suspend fun allProducts(): List<Product>
    @Upsert suspend fun upsertProducts(products: List<Product>)
    @Query("SELECT * FROM personal") fun personal(): Flow<List<Personal>>
    @Query("SELECT * FROM personal WHERE productId=:id") suspend fun getPersonal(id: Int): Personal?
    @Upsert suspend fun upsertPersonal(row: Personal)
    @Query("SELECT * FROM plans ORDER BY createdAt DESC, id DESC") fun plans(): Flow<List<Plan>>
    @Insert suspend fun insertPlan(plan: Plan): Long
    @Query("UPDATE plans SET name=:name, budgetCents=:budget WHERE id=:id") suspend fun updatePlan(id: Long, name: String, budget: Long)
    @Query("DELETE FROM plans WHERE id=:id") suspend fun deletePlan(id: Long)
    @Query("SELECT * FROM entries") fun entries(): Flow<List<PlanEntry>>
    @Query("SELECT * FROM entries WHERE planId=:plan AND productId=:product") suspend fun entry(plan: Long, product: Int): PlanEntry?
    @Upsert suspend fun upsertEntry(entry: PlanEntry)
    @Query("DELETE FROM entries WHERE planId=:plan AND productId=:product") suspend fun deleteEntry(plan: Long, product: Int)
    @Query("SELECT * FROM history ORDER BY seenAt DESC") fun history(): Flow<List<Visit>>
    @Upsert suspend fun visit(visit: Visit)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("SELECT * FROM presets ORDER BY name") fun presets(): Flow<List<Preset>>
    @Upsert suspend fun savePreset(preset: Preset)
    @Query("DELETE FROM presets WHERE id=:id") suspend fun deletePreset(id: Long)
    @Query("SELECT * FROM sync_meta WHERE id=1") suspend fun syncMeta(): SyncMeta?
    @Upsert suspend fun syncMeta(meta: SyncMeta)
    @Query("SELECT * FROM price_points ORDER BY observedAt DESC") fun pricePoints(): Flow<List<PricePoint>>
    @Upsert suspend fun pricePoint(point: PricePoint)
    @Query("DELETE FROM price_points WHERE productId=:id AND observedAt NOT IN (SELECT observedAt FROM price_points WHERE productId=:id ORDER BY observedAt DESC LIMIT 30)")
    suspend fun trimPrices(id: Int)
}
@Database(entities = [Product::class, Personal::class, Plan::class, PlanEntry::class,
    Visit::class, Preset::class, SyncMeta::class, PricePoint::class], version = 1, exportSchema = true)
abstract class PocketDatabase : RoomDatabase() { abstract fun dao(): PocketDao }
