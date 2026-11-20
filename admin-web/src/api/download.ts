import { ApiError, type FetchResult, toApiError, UNEXPECTED } from './errors';

/**
 * How long the blob URL lives after the click. The browser reads the blob after the click returns, and some browsers
 * cancel the download when the URL is revoked at once. FileSaver.js waits 40 s for the same reason.
 */
export const REVOKE_AFTER_MS = 40_000;

/**
 * Saves the answer of a CSV route as a file. A plain link cannot do it, because the request needs the `Authorization`
 * header. The caller passes an `api.GET(..., { parseAs: 'blob' })` call, so the client's middleware adds the header
 * from the session store and turns a failed fetch into an {@link ApiError}. A non-2xx answer becomes an
 * {@link ApiError} too. Returns the file name it saved under.
 */
export async function downloadCsv(call: Promise<FetchResult>, fallbackName: string): Promise<string> {
  const { data, error, response } = await call;
  if (!response.ok) throw toApiError(response, error);
  if (!(data instanceof Blob)) {
    throw new ApiError({ status: response.status, code: UNEXPECTED, title: 'The answer is not a file' });
  }
  const name = fileNameOf(response.headers.get('Content-Disposition')) ?? fallbackName;
  saveBlob(data, name);
  return name;
}

/** Clicks a temporary link to a blob URL with the `download` attribute, then revokes the URL. */
export function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.hidden = true;
  document.body.append(link);
  try {
    link.click();
  } finally {
    link.remove();
    setTimeout(() => {
      URL.revokeObjectURL(url);
    }, REVOKE_AFTER_MS);
  }
}

/**
 * The file name in a `Content-Disposition` header: `attachment; filename=drivers.csv` or `filename="a b.csv"`. The
 * server sends only plain ASCII names, so `filename*` is not read. Path parts are dropped, as a browser would.
 */
export function fileNameOf(header: string | null): string | undefined {
  if (header === null) return undefined;
  const match = /(?:^|;)\s*filename\s*=\s*(?:"([^"]*)"|([^;]*))/i.exec(header);
  const raw = (match?.[1] ?? match?.[2] ?? '').trim();
  const name = raw.split(/[/\\]/).pop() ?? '';
  return name === '' ? undefined : name;
}
