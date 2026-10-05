package com.techfullymade.afterchime.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.SecretKey

class NotificationReducer(
  private val ownPackageName: String,
  private val sourceHmacKey: SecretKey,
  private val timeZone: ZoneId,
  excludedRawCategories: Set<String> = emptySet(),
) {
  private val excludedRawCategories = excludedRawCategories + SYSTEM_CATEGORY

  init {
    require(ownPackageName.isNotBlank())
  }

  fun reduce(statusBarNotification: StatusBarNotification): ReducedNotification? {
    val rawPackageName = statusBarNotification.packageName
    if (rawPackageName == ownPackageName) {
      return null
    }
    if (isBlockedSensitiveSource(rawPackageName)) {
      return null
    }

    val postedNotification = statusBarNotification.notification
    return reduce(
      sourceDigest = sourceDigest(rawPackageName),
      rawCategory = postedNotification.category,
      isGroupSummary = postedNotification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
      isOngoing = postedNotification.flags and Notification.FLAG_ONGOING_EVENT != 0,
      occurredAtEpochMillis = statusBarNotification.postTime,
    )
  }

  private fun reduce(
    sourceDigest: ByteArray,
    rawCategory: String?,
    isGroupSummary: Boolean,
    isOngoing: Boolean,
    occurredAtEpochMillis: Long,
  ): ReducedNotification? {
    if (
      isGroupSummary ||
      isOngoing ||
      rawCategory in excludedRawCategories
    ) {
      return null
    }

    return ReducedNotification(
      occurredAtEpochMillis = occurredAtEpochMillis,
      localHour = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(timeZone).hour,
      category = categoryFor(rawCategory),
      sourceToken = sourceDigest.toHex(),
      sourceColourRgb = sourceDigest.toColourRgb(),
    )
  }

  private fun sourceDigest(rawPackageName: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(sourceHmacKey)
    mac.update(SOURCE_TOKEN_DOMAIN)
    return mac.doFinal(rawPackageName.toByteArray(UTF_8))
  }

  private fun isBlockedSensitiveSource(rawPackageName: String): Boolean =
    MessageDigest.getInstance("SHA-256")
      .digest(rawPackageName.toByteArray(UTF_8))
      .toHex() in SENSITIVE_SOURCE_DIGESTS

  private fun categoryFor(rawCategory: String?): CoarseNotificationCategory = when (rawCategory) {
    Notification.CATEGORY_ALARM -> CoarseNotificationCategory.ALARM
    Notification.CATEGORY_CALL -> CoarseNotificationCategory.CALL
    Notification.CATEGORY_EMAIL -> CoarseNotificationCategory.EMAIL
    Notification.CATEGORY_EVENT -> CoarseNotificationCategory.EVENT
    Notification.CATEGORY_MESSAGE -> CoarseNotificationCategory.MESSAGE
    Notification.CATEGORY_NAVIGATION -> CoarseNotificationCategory.NAVIGATION
    Notification.CATEGORY_PROGRESS -> CoarseNotificationCategory.PROGRESS
    Notification.CATEGORY_REMINDER -> CoarseNotificationCategory.REMINDER
    Notification.CATEGORY_SOCIAL -> CoarseNotificationCategory.SOCIAL
    Notification.CATEGORY_TRANSPORT -> CoarseNotificationCategory.TRANSPORT
    Notification.CATEGORY_WORKOUT -> CoarseNotificationCategory.WORKOUT
    else -> CoarseNotificationCategory.OTHER
  }

  private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xFF)
  }

  private fun ByteArray.toColourRgb(): Int =
    ((this[0].toInt() and 0xFF) shl 16) or
      ((this[1].toInt() and 0xFF) shl 8) or
      (this[2].toInt() and 0xFF)

  private companion object {
    val SOURCE_TOKEN_DOMAIN = "afterchime:source-token:v1\u0000".toByteArray(UTF_8)
    const val SYSTEM_CATEGORY = "sys"

    // One-way package digests keep the built-in fail-closed policy out of logs, storage, and UI.
    val SENSITIVE_SOURCE_DIGESTS = setOf(
      // Financial and payment apps.
      "af8748da7c832517f048d2e41747e78686e9e207476a0aa3385c11293c9f3dd4",
      "328edc25808f9ce03f460347d062132110be1304eb72fbb7e430b8ae7cf6f7fc",
      "f1a760976a5b9a86be181087f7f13ebfb6dfd840849b69a192dcaf5825fa7f1c",
      "2b5c5aba6dc8b1b45b216b8d2bd0406264078c43573608db6d7479c0a1adb193",
      "5a49a902980687d0c9474684b6ac1189096a09843de812b3562e2e1c7a014bba",
      "330c6258e9c9ff2a7454b3399c42881542d677286df0d5cda59503ea8e9d2e6f",
      "f23ae638a9af466a16e1960e574420cd1a078d8d53402a6edd55d7ac982f9ab5",
      "0537d16900012898a3ebd47c8ae614d88f9b09cb003cf8a37bf85c5e27a6622e",
      "7dbb2254b560e9751fab615057ab0a1ff7c8575dbfbb98dc3440beca708a1259",
      "8e7b0cccc0b1a64574e95efbeb9646748820795231e93107cb2f1f88a952a332",
      "e7cdaa0457d48fe4d286a086f0b559ed462c5686cd7f40e6f257a90bc98dad19",
      "d2a0e59be7b92b6a1189cf52a0590b64b60571d3426edfb3039e5e17a6667b80",
      "c086eb31ded689f1db9b493cf134f6aa739a5b3f3b92d06238625b9480725046",
      "b0315aec150f5fc38f63f20451f9ccf17ae70adeaa9d3990168b8ca2dcb33fe1",
      "a6016cd2e926c220de966fdb4906ab8d16d70df24b37334152a79a0b8f3fa58e",
      "e37f48642c2e2502f9439c61a2bdac34c9b14486b55c86b5f9982b82a8537449",
      "ec5ffeb02ea5f26961eae9059ac63e0c7cf52952ad250eca531264fb99396e60",
      "3dbc16d197e67b051c259980e463655db4e8b3d6827e93875de172112ae831e9",
      "5ff6feab9fe4d6caa7bbc8728b031f78f6b067c3cf354751ef7e1d7ee77172f7",
      "68acc80df714c32a7e33e0b38f9ec9f142ad8022d5be7a85391f53462e23c636",
      "19f4164f01d34f30c88de6379574608c3cedf9d8687e57ea362a06c60a577421",
      "a35822e186e11efdda0f52ac8c9ed3f1efb19f2ca1b9abf9858c471fdbdfa1bb",
      "5311f9dae13f78726054e85f7c75ed606bddf5ba61dd9c042cbfae7d65bead23",
      "88812f7306a45dd7c8039be5ad853462c5e75851c62c2b37c16a8ddb97dcaf83",
      "1c4ba1f74b5beceb499d4ea80e50f9fdb8bc7e5537f31e91f15e5d08d5cd1717",
      "87711eacb7ff6167700fd83d17d269202da218d8b5088577de0424bc6341ca8a",
      "6427ef9cd5974d273e961f6c0784be73522700f1d6e62f91633373b274590ce7",
      "8785cdae43333543cd8fe3ee00373dbbb9ff04fa488169a222b112f6625ce4d1",
      "83abb0350047081f462537de7b037893fda84bf31770a66bc89f2b837126cfa9",
      "df05a8bee23314d9b16ca3c9fb58c54b5f14ace2c7e8f3e32e960bbae0a89148",
      "907d88e72257f6ac7f56bccb624bbe944b2f193fc9d107eb4101a6c3a6eaba89",
      "e1e2470e8e4779087386bbb4da7145e46be7cbd56d93586ba47fcfb7c1c9479b",
      "b6804fce093795e8527460b0797e9962939513f53c8065b9f7c0722ac4d8e7c0",
      "fef30b8cb219f563e1cad09bb1033bbdf50b93d40991d5da00ede515fc6acfe2",
      "beec4d596070b5c4362deb0bdd50b33e2346872bf7eaa54c15273b67b7d4ef9a",
      // Password managers and authenticators.
      "29536d56a68675e046375c39c37818433635a9d3e08caf17916568189ca950ae",
      "b96c4a449ccef58e158f33cdd632c516b207f9cdda868f8f1385721486965adb",
      "6dfcff46582b73c17b66083a1f181b55e963098aef445bc8fb6a707349f7bb6d",
      "9036eb45186ccffc023b3383662224a9c8f9a1c6759a311701641e57ee945012",
      "e6e2c74e25ad861aef40d7b4bdceae25cf8479703efcf8d7259ed2b1b6f0d181",
      "d7ed77e9bf4bdb886a38ec28d7782c9c4d88139cbf842f5a28de28c553295919",
      "3feccd8d6916477c1b6ef0ca6f641634d0711076dabc41afa59b69a339d0e5fe",
      "2562b8658b4d641b938b071579c825a841b48a5ac3b333acd337fc0128a5fca5",
      "889851c11b414def02018adb60e26415fa6a40a786e7a196c5e0b964853cc505",
      "bbeebb4604d12cfa2d3cd3adb793a3895777fe48ad31e317c32f6812beefd57f",
      "04c0bdebae2968ae9f2aa1022a32cdf9f75bc8be783ba9b030a4a95fbcb28594",
      "e4428c01e901b36ea846e0a5bc0bd9b8d8e67868b575f148c62cee5ff4986c2d",
      "6b0652e5606731c12bd65495cf8690af9a8c65d1bf0d8f24228011b5260805dc",
      "ae032267e862b11052da712fd115a41b7247c207f53c67e3d857ea31d4502c04",
      "d940787db5cd965deb839daef0ac3cc8b49a5321bfb2cbb0be19ff0ecae6c757",
      "69f27d6cce5f5a5840bd608d2af0dfee9e3228b389cf2961b769b42a16c00df0",
      "6e557e0ba53597e88766abd6941af3f4274210c46f8bae6ee796570be0367f51",
      "529115842946c8d32b3b83f53e9957e067369a9113c9fe13394ea1246dc3c613",
      "7218cc56b869343ce82195c6fe8f80d69f2afd4f96146a4395e58d9da2d3fd1d",
      // VPN and private-network apps.
      "7377d87e471e6c89e90c9768ea096b09277ebb1afda23bb93d58a2c8bf839bb8",
      "41b89704774bc73c5e9540ec47da4ebaf43a62c74935e04ec5ec8557a029fd01",
      "b70e3e3127cfa375f6d7dc149c7d51c2871ec467e978215278a3f30e3628d844",
      "ca5ed55213e1dcbbd3b66c8a7412513d3ec281d994fd20671da683d45484aa2d",
      "5a757e55d8c45f1c0da196c1a0bb76c41b9b1f0cdd05e78b7ca5f162f8e91475",
      "088f59308c3a5d17a67be48c61983e069994d88b825e287708fe894e6d89d55d",
      "511c1fb20685d12195ebfff9aa6e38b5246f02ded7684cf5db144ab4f3ea5c1b",
      "5df8c299417a8280ec275aa59e59d4eb3371267883241567406c57b56121879f",
      "63d428504e55ee9ceef23dcd4c0ff16e687cf37acbaef8e276bd9cb315e5e49b",
      "75a23f5c0b66f8293113284b88ba41a96e26c7d52f2f35030b6ccb0cdcf03d2c",
      "6bc41d39e9db7d69ca3bf2248c1b3ed317d80eebd618597c7c4897222740227f",
      "9d98727c4276e9093766e691ba5e16b1165f0d8784515a702f622b1ae1dfbe03",
      "bd2bda0d75b9cb254ad263fd293d602d7839981e8f15ebcfbb8a5f79d8506ba0",
      "d45ad481b1144c27a2e771724cd275d4035e8b83721e71be94de6d92a03ed646",
      "cf5e73456e3fbea09a1c0f3c5c6672ed5e03c5d305b0e0013a82af2e233efc3e",
      "7576e34ae02c8f0760fbb5f50eaaff606373d305c2bccc41fa7ef9877e9cf4ed",
    )
  }
}
