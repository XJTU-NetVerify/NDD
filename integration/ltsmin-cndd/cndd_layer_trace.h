/* Optional diagnostic trace shared by every backend in this frontend binary.
 * Enumerate actual tuples; compare sorted records offline, never just counts. */
struct cndd_trace_context { FILE *file; int width; };
static void cndd_trace_tuple(void *opaque, int *tuple) {
    struct cndd_trace_context *c = opaque;
    for (int i = 0; i < c->width; ++i) fprintf(c->file, "%s%d", i ? "," : "", tuple[i]);
    if (fputc('\n', c->file) == EOF) Abort("Cannot write layer trace");
}
static void cndd_trace_set(const char *directory, int level, const char *kind, vset_t set, int width) {
    char path[4096]; int len = snprintf(path, sizeof(path), "%s/%03d-%s.csv", directory, level, kind);
    if (len < 0 || (size_t)len >= sizeof(path)) Abort("Layer trace path too long");
    FILE *f = fopen(path, "wx"); if (!f) Abort("Cannot create layer trace %s", path);
    struct cndd_trace_context c = {f, width}; vset_enum(set, cndd_trace_tuple, &c);
    if (fclose(f)) Abort("Cannot close layer trace %s", path);
}
static void cndd_trace_level(vset_t frontier, vset_t visited, int level, int width) {
    const char *dir = getenv("LTSMIN_LAYER_TRACE"); if (!dir || !*dir) return;
    const char *levels = getenv("LTSMIN_LAYER_LEVELS");
    if (levels && *levels) {
        int selected = 0;
        for (const char *p = levels; *p;) {
            char *end; long value = strtol(p, &end, 10);
            if (end == p || value < 0 || value > 1000000000L || (*end && *end != ','))
                Abort("Invalid LTSMIN_LAYER_LEVELS");
            if (value == level) selected = 1;
            if (*end == ',' && !end[1]) Abort("Invalid LTSMIN_LAYER_LEVELS");
            p = *end ? end + 1 : end;
        }
        if (!selected) return;
    }
    cndd_trace_set(dir, level, "frontier", frontier, width);
    cndd_trace_set(dir, level, "visited", visited, width);
}
