Grafika
=======

Welcome to Grafika, a dumping ground for Android graphics & media hacks.

Grafika is:
- A collection of hacks exercising graphics features.
- An SDK app, developed for API 18 (Android 4.3).  While some of the code
  may work with older versions of Android, no effort will be made to
  support them.
- Open source (Apache 2 license), copyright by Google.  So you can use the
  code according to the terms of the license (see "LICENSE").
- A perpetual work-in-progress.  It's updated whenever the need arises.

However:
- It's not stable.
- It's not polished or well tested.  Expect the UI to be ugly and awkward.
- It's not intended as a demonstration of the proper way to do things.
  The code may handle edge cases poorly or not at all.
- It's not documented.
- It's not part of the Android Open Source Project.  We cannot accept
  contributions to Grafika, even if you have an AOSP CLA on file.
- It's NOT AN OFFICIAL GOOGLE PRODUCT.  It's just a bunch of stuff that
  got thrown together on company time and equipment.
- It's generally just not supported.

There is some overlap with the code on http://www.bigflake.com/mediacodec/.  The code there largely consists of "headless" CTS tests, which are designed to be robust, self-contained, and largely independent of the usual app lifecycle issues.  Grafika is a traditional app, and makes an effort to handle app issues correctly (like not doing lots of work on the UI thread).

Features are added to Grafika as the need arises, often in response to developer complaints about correctness or performance problems in the platform (either to confirm that the problems exist, or demonstrate that they don't).

