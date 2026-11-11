#include <hre/config.h>
#include <hre/user.h>
#include <vset-lib/vector_set.h>
#include <vset-lib/vdom_object.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern void cndd_collect(void);
extern void cndd_shutdown(void);
#define CHECK(x) do { if (!(x)) Abort("C-NDD adapter test failed: %s at %d", #x, __LINE__); } while (0)
static struct poptOption options[] = {
    {NULL, 0, POPT_ARG_INCLUDE_TABLE, vset_options, 0, "Vector set options", NULL}, POPT_TABLEEND
};
static double count(vset_t s) { long nodes; double n; vset_count(s, &nodes, &n); CHECK(nodes >= 0); return n; }
struct enumeration { vset_t src; int calls; };
static void mutate(void *opaque, int *tuple) {
    struct enumeration *e = opaque; CHECK(tuple[0] == 1 || tuple[0] == 2);
    if (!e->calls) { vset_clear(e->src); cndd_collect(); }
    ++e->calls;
}
static void copy_tuple(vset_t dst, void *unused, int *tuple) { (void)unused; vset_add(dst, tuple); }
static void destroy_source(void *opaque, int *tuple) {
    struct enumeration *e = opaque; CHECK(tuple[0] == 1 || tuple[0] == 2);
    if (!e->calls) { vset_destroy(e->src); cndd_collect(); }
    ++e->calls;
}
static void learn(vrel_t r, void *unused, int *tuple) { (void)unused; int next = tuple[0] + 1; vrel_add(r, tuple, &next); cndd_collect(); }
static void run(void) {
    vdom_t d = vdom_create_domain(3, VSET_CNDD); CHECK(d && vdom_separates_rw(d));
    vset_t s = vset_create(d, -1, NULL), out = vset_create(d, -1, NULL), saved = vset_create(d, -1, NULL);
    int a[] = {1,7,4}, b[] = {2,8,5}; vset_add(s,a); vset_add(s,b); vset_add(s,a); CHECK(count(s) == 2);
    vset_copy(saved,s); vset_update(out,s,copy_tuple,NULL); CHECK(vset_equal(out,s));
    int p0 = 0, p2 = 2, both[] = {0,2}; vset_t rproj = vset_create(d,1,&p0), wproj = vset_create(d,1,&p2), joined = vset_create(d,2,both);
    vset_project(rproj,s); vset_project(wproj,s); vset_join(joined,rproj,wproj); CHECK(count(joined) == 4);
    vset_t eps = vset_create(d,0,NULL); vset_project(eps,s); CHECK(count(eps) == 1 && vset_member(eps,NULL));
    vset_project_minus(rproj,s,rproj); CHECK(vset_is_empty(rproj)); vset_project(rproj,s);
    vrel_t r = vrel_create_rw(d,1,&p0,1,&p2); int src=1,dst=9;
    vrel_add(r,&src,&dst); vset_next(out,s,r); int expected[]={1,7,9}; CHECK(count(out)==1 && vset_member(out,expected));
    long nodes; double pairs; vrel_count(r,&nodes,&pairs); CHECK(pairs==65536.0);
    vset_next(s,s,r); CHECK(vset_equal(out,s)); vset_copy(s,saved); ((struct vector_domain_shared *)d)->rel_destroy(r);
    r=vrel_create_rw(d,0,NULL,1,&p2); int copy=1; vrel_add_cpy(r,NULL,&dst,&copy); vset_next(out,s,r); CHECK(vset_equal(out,s)); ((struct vector_domain_shared *)d)->rel_destroy(r);
    r=vrel_create_rw(d,1,&p0,1,&p2); vrel_update(r,rproj,learn,NULL); vset_next(out,s,r);
    int x[]={1,7,2}, y[]={2,8,3}; CHECK(count(out)==2 && vset_member(out,x) && vset_member(out,y));
    vset_union(out,s); CHECK(count(out)==4); vset_minus(out,s); CHECK(count(out)==2);
    vset_intersect(out,s); CHECK(vset_is_empty(out));
    vset_add(out,a); vset_zip(out,s); CHECK(count(out)==2 && count(s)==1 && vset_member(s,b));
    vset_copy(s,saved); struct enumeration e={s,0}; vset_enum(s,mutate,&e); CHECK(e.calls==2 && vset_is_empty(s));
    vset_t temporary=vset_create(d,-1,NULL); vset_copy(temporary,saved);
    struct enumeration destroyed={temporary,0}; vset_enum(temporary,destroy_source,&destroyed); CHECK(destroyed.calls==2);
    vset_example(saved,x); CHECK(vset_member(saved,x));
    cndd_collect(); CHECK(count(saved)==2);
    /* Frontend has no domain-destroy entry point. Test cleanup of both
     * explicitly destroyed objects and remaining frontend-owned objects. */
    vset_destroy(s); ((struct vector_domain_shared *)d)->rel_destroy(r); cndd_shutdown();
    d=vdom_create_domain(0,VSET_CNDD); s=vset_create(d,-1,NULL); r=vrel_create(d,0,NULL);
    vset_add(s,NULL); vrel_add(r,NULL,NULL); vset_next(s,s,r); CHECK(count(s)==1); cndd_shutdown();
}
int main(int argc, char **argv) {
    HREinitBegin(argv[0]); HREaddOptions(options,"C-NDD adapter regression"); HREinitStart(&argc,&argv,0,0,NULL,NULL);
    for(int i=0;i<3;++i) run();
    puts("CNDD_ADAPTER_PASS repeats=3"); return 0;
}
