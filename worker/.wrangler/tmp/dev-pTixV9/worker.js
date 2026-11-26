var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// .wrangler/tmp/bundle-WZ3bzX/strip-cf-connecting-ip-header.js
function stripCfConnectingIPHeader(input, init) {
  const request = new Request(input, init);
  request.headers.delete("CF-Connecting-IP");
  return request;
}
__name(stripCfConnectingIPHeader, "stripCfConnectingIPHeader");
globalThis.fetch = new Proxy(globalThis.fetch, {
  apply(target, thisArg, argArray) {
    return Reflect.apply(target, thisArg, [
      stripCfConnectingIPHeader.apply(null, argArray)
    ]);
  }
});

// worker.js
var CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET, OPTIONS" };
var UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
var PARTY = "https://vidsrc.party";
function withCors(r, extra = {}) {
  const h = new Headers(r.headers);
  for (const [k, v] of Object.entries({ ...CORS, ...extra }))
    h.set(k, v);
  h.delete("set-cookie");
  return new Response(r.body, { status: r.status, headers: h });
}
__name(withCors, "withCors");
async function cached(target, init, seconds, ctx) {
  const cache = caches.default;
  const key = new Request(target);
  const hit = await cache.match(key);
  if (hit)
    return withCors(hit);
  const r = await fetch(target, init);
  const res = withCors(r, { "Cache-Control": `public, max-age=${seconds}` });
  if (r.ok)
    ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}
__name(cached, "cached");
var worker_default = {
  async fetch(req, env, ctx) {
    if (req.method === "OPTIONS")
      return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      if (p.startsWith("/tmdb/")) {
        const t = new URL("https://api.themoviedb.org/3/" + p.slice(6));
        url.searchParams.forEach((v, k) => t.searchParams.set(k, v));
        t.searchParams.set("api_key", env.TMDB_KEY || "");
        if (!t.searchParams.has("language"))
          t.searchParams.set("language", "en-US");
        return cached(t.toString(), {}, 6 * 3600, ctx);
      }
      if (p === "/wyzie") {
        if (!env.WYZIE_KEY)
          return new Response("[]", { headers: { ...CORS, "Content-Type": "application/json" } });
        const t = new URL("https://sub.wyzie.io/search");
        url.searchParams.forEach((v, k) => t.searchParams.set(k, v));
        t.searchParams.set("key", env.WYZIE_KEY);
        return cached(t.toString(), {}, 6 * 3600, ctx);
      }
      if (p === "/intro") {
        return cached("https://api.theintrodb.org/v4/media" + url.search, {}, 24 * 3600, ctx);
      }
      if (p.startsWith("/party/api/")) {
        const r = await fetch(PARTY + p.slice(6) + url.search, {
          headers: { Referer: PARTY + "/", Origin: PARTY, Accept: "application/json", "User-Agent": UA }
        });
        return withCors(r, { "Cache-Control": "no-store" });
      }
      if (p === "/sub") {
        const u = url.searchParams.get("u") || "";
        if (!/^https:\/\//.test(u))
          return new Response("bad url", { status: 400, headers: CORS });
        const r = await fetch(u, { headers: { "User-Agent": UA, Referer: new URL(u).origin + "/" } });
        if (!r.ok)
          return new Response("", { status: r.status, headers: CORS });
        const buf = await r.arrayBuffer();
        if (buf.byteLength > 3 << 20)
          return new Response("too big", { status: 413, headers: CORS });
        return new Response(buf, { headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=86400" } });
      }
    } catch (e) {
      return new Response("helper error", { status: 502, headers: CORS });
    }
    return new Response("Movio helper is running", { headers: { ...CORS, "Content-Type": "text/plain" } });
  }
};

// ../../.npm/_npx/0eedb5afd4158ff3/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
}, "drainBody");
var middleware_ensure_req_body_drained_default = drainBody;

// ../../.npm/_npx/0eedb5afd4158ff3/node_modules/wrangler/templates/middleware/middleware-miniflare3-json-error.ts
function reduceError(e) {
  return {
    name: e?.name,
    message: e?.message ?? String(e),
    stack: e?.stack,
    cause: e?.cause === void 0 ? void 0 : reduceError(e.cause)
  };
}
__name(reduceError, "reduceError");
var jsonError = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } catch (e) {
    const error = reduceError(e);
    return Response.json(error, {
      status: 500,
      headers: { "MF-Experimental-Error-Stack": "true" }
    });
  }
}, "jsonError");
var middleware_miniflare3_json_error_default = jsonError;

// .wrangler/tmp/bundle-WZ3bzX/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default,
  middleware_miniflare3_json_error_default
];
var middleware_insertion_facade_default = worker_default;

// ../../.npm/_npx/0eedb5afd4158ff3/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
__name(__facade_register__, "__facade_register__");
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
__name(__facade_invokeChain__, "__facade_invokeChain__");
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}
__name(__facade_invoke__, "__facade_invoke__");

// .wrangler/tmp/bundle-WZ3bzX/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof __Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
__name(__Facade_ScheduledController__, "__Facade_ScheduledController__");
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = /* @__PURE__ */ __name(function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  }, "fetchDispatcher");
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = /* @__PURE__ */ __name(function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      }, "dispatcher");
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
__name(wrapExportedHandler, "wrapExportedHandler");
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = (request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    };
    #dispatcher = (type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    };
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
__name(wrapWorkerEntrypoint, "wrapWorkerEntrypoint");
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default
};
//# sourceMappingURL=worker.js.map
