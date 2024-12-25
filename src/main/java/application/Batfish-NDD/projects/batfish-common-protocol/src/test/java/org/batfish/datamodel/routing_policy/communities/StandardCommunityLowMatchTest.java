package org.batfish.datamodel.routing_policy.communities;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.testing.EqualsTester;
import org.apache.commons.lang3.SerializationUtils;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.routing_policy.expr.IntComparator;
import org.batfish.datamodel.routing_policy.expr.IntComparison;
import org.batfish.datamodel.routing_policy.expr.LiteralInt;
import org.junit.Test;

/** Test of {@link StandardCommunityLowMatch}. */
public final class StandardCommunityLowMatchTest {

  private static final StandardCommunityLowMatch OBJ =
      new StandardCommunityLowMatch(new IntComparison(IntComparator.EQ, new LiteralInt(1)));

  @Test
  public void testJacksonSerialization() {
    assertThat(BatfishObjectMapper.clone(OBJ, StandardCommunityLowMatch.class), equalTo(OBJ));
  }

  @Test
  public void testJavaSerialization() {
    assertThat(SerializationUtils.clone(OBJ), equalTo(OBJ));
  }

  @Test
  public void testEquals() {
    new EqualsTester()
        .addEqualityGroup(new Object())
        .addEqualityGroup(
            OBJ,
            OBJ,
            new StandardCommunityLowMatch(new IntComparison(IntComparator.EQ, new LiteralInt(1))))
        .addEqualityGroup(
            new StandardCommunityLowMatch(new IntComparison(IntComparator.GT, new LiteralInt(1))))
        .testEquals();
  }
}
