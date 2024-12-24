package org.batfish.representation.cisco_xr;

import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class RoutePolicyElseBlock implements Serializable {

  public RoutePolicyElseBlock() {
    _stmtList = ImmutableList.of();
  }

  public void addStatement(RoutePolicyStatement stmt) {
    _stmtList =
        ImmutableList.<RoutePolicyStatement>builderWithExpectedSize(_stmtList.size() + 1)
            .addAll(_stmtList)
            .add(stmt)
            .build();
  }

  public @Nonnull List<RoutePolicyStatement> getStatements() {
    return _stmtList;
  }

  private @Nonnull List<RoutePolicyStatement> _stmtList;
}
