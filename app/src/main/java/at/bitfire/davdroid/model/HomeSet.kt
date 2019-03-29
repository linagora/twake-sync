package at.bitfire.davdroid.model

import androidx.room.*
import okhttp3.HttpUrl

@Entity(tableName = "homeset",
        foreignKeys = [
            ForeignKey(entity = Service::class, parentColumns = arrayOf("id"), childColumns = arrayOf("serviceId"), onDelete = ForeignKey.CASCADE)
        ],
        indices = [
            // index by service; no duplicate URLs per service
            Index("serviceId", "url", unique = true)
        ]
)
data class HomeSet(
    @PrimaryKey(autoGenerate = true)
    var id: Long,

    var serviceId: Long,
    var url: HttpUrl
)