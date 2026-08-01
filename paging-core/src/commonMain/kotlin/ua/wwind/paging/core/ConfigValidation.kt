package ua.wwind.paging.core

/**
 * Message for the `cacheSize >= preloadSize` invariant shared by every pager configuration.
 *
 * Both values are radii in indices around the last accessed key, so a cache narrower than the
 * preload window can never hold what that window fetches: the arriving payload is pruned to the
 * cache radius and the remainder is dropped on arrival, while the requests that produced it keep
 * running.
 *
 * @param preloadName name the preload radius carries in the configuration being validated -
 * `preloadSize` in [Pager] and `StreamingPagerConfig`, `prefetchSize` in [PagingMediatorConfig].
 */
internal fun cacheRadiusTooSmallMessage(cacheSize: Int, preloadName: String, preloadSize: Int): String =
    "cacheSize ($cacheSize) must be >= $preloadName ($preloadSize): the pager retains only " +
        "+/-cacheSize items around the last accessed key, so a wider preload radius fetches " +
        "data that is discarded on arrival"
