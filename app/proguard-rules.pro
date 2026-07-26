# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Firebase (Auth, Firestore, Messaging), Glide, and play-services-ads all
# ship their own consumer ProGuard rules bundled in their AARs, applied
# automatically — no app-level -keep rules added here for them.
#
# This app has no custom Parcelable, Gson/@SerializedName, or other
# reflection-based serialization classes (Firestore's UserProfile/Match/etc.
# all use plain constructors + DocumentSnapshot field reads, not
# .toObject()), which is the most common source of rules an app like this
# would otherwise need to add by hand. If a release build ever shows a
# ClassNotFoundException or missing-method crash traceable to R8 stripping
# something, add a targeted -keep rule for that specific class here rather
# than a broad blanket rule.

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
