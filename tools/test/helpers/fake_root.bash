# Fake-repo helper for the tools/ut.sh tests. Loaded via `load 'helpers/fake_root'`.
#
# ut.sh derives both its default Flink version and the set it accepts from
# files in the tree above it, so proving either is derived rather than pinned
# needs a tree that disagrees with the real repo. Requires $UT_SH to point at
# the script under test.

# Builds a throwaway tools/.. tree and echoes its root. $1 is the literal
# <properties> body of the root pom, so a caller can pin a well-formed version,
# a malformed one, or none at all. Every remaining argument becomes a
# dist/flink-<version> directory, which is what ut.sh reads its supported set
# from; passing none leaves a tree that carries no dist module at all.
make_fake_root() {
    local properties="$1"
    shift
    local fake="$BATS_TEST_TMPDIR/fake" version
    mkdir -p "$fake/tools" "$fake/python"
    cp "$UT_SH" "$fake/tools/ut.sh"
    printf '<project>\n  <properties>\n%s\n  </properties>\n</project>\n' \
        "$properties" >"$fake/pom.xml"
    for version in "$@"; do
        mkdir -p "$fake/dist/flink-${version}"
    done
    echo "$fake"
}

# Shorthand for the common case: a well-formed <flink.version> plus the dist
# modules the tree should carry.
make_fake_root_pinning() {
    local pom_version="$1"
    shift
    make_fake_root "    <flink.version>${pom_version}</flink.version>" "$@"
}
