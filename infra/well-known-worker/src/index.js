/**
 * Serves the private "well-known" R2 bucket at the request path.
 *
 * Cloudflare Origin Rules cannot retarget an origin on the Free plan - host
 * header, SNI and DNS record overrides are all Enterprise-only - so the bucket
 * is reached through an R2 binding instead. env.WELL_KNOWN is an in-network
 * handle, not a URL, so the bucket needs no public hostname and its public
 * access stays switched off.
 */
export default {
	async fetch(request, env) {
		// Anything this Worker does not own passes through to the site origin.
		if (request.method !== "GET" && request.method !== "HEAD") {
			return fetch(request);
		}

		// R2 keys carry no leading slash: "/.well-known/assetlinks.json" is stored
		// as ".well-known/assetlinks.json". The routes match exact paths, so this
		// is the whole path-to-key mapping.
		const key = new URL(request.url).pathname.slice(1);
		const object =
			request.method === "HEAD"
				? await env.WELL_KNOWN.head(key)
				: await env.WELL_KNOWN.get(key);

		if (object === null) {
			return fetch(request);
		}

		const headers = new Headers();
		// Replays the Content-Type recorded at upload time. Digital Asset Links
		// requires application/json, which the deploy step sets via --content-type.
		object.writeHttpMetadata(headers);
		headers.set("etag", object.httpEtag);

		// No Cache-Control on purpose: signing-fingerprint.yml re-fetches this URL
		// within seconds of writing the object and compares bytes, so a cached copy
		// would fail that check on a legitimate update.
		return new Response(request.method === "HEAD" ? null : object.body, { headers });
	},
};
