package io.github.rahulbsw.protoweft.evals.discovery.cost;

/** Thrown before a model call that the cost guard refuses; the run stops cleanly. */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
