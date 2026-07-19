"""urllib helpers that do not forward credentials to a different origin."""

import urllib.error
import urllib.parse
import urllib.request


def _origin(url):
    parsed = urllib.parse.urlsplit(url)
    default_port = 443 if parsed.scheme.lower() == "https" else 80
    return parsed.scheme.lower(), (parsed.hostname or "").lower(), parsed.port or default_port


class SameOriginRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Preserve HTTPS redirects but strip Authorization across origins."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is None:
            return None
        old_scheme = urllib.parse.urlsplit(req.full_url).scheme.lower()
        new_scheme = urllib.parse.urlsplit(redirected.full_url).scheme.lower()
        if old_scheme == "https" and new_scheme != "https":
            raise urllib.error.HTTPError(
                redirected.full_url, code, "refusing HTTPS downgrade", headers, fp
            )
        if _origin(req.full_url) != _origin(redirected.full_url):
            redirected.remove_header("Authorization")
            for key in list(redirected.unredirected_hdrs):
                if key.lower() == "authorization":
                    del redirected.unredirected_hdrs[key]
        return redirected


_OPENER = urllib.request.build_opener(SameOriginRedirectHandler())


def urlopen(request, *, timeout):
    return _OPENER.open(request, timeout=timeout)
