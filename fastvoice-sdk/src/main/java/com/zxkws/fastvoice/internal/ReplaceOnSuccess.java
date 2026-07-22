package com.zxkws.fastvoice.internal;

/** A resource slot whose current value changes only after replacement creation succeeds. */
final class ReplaceOnSuccess<T> {
    interface Factory<T> { T create(); }

    private T current;

    synchronized T replace(Factory<T> factory) {
        T replacement = factory.create();
        if (replacement == null) throw new IllegalStateException("replacement must not be null");
        T previous = current;
        current = replacement;
        return previous;
    }

    synchronized T current() {
        return current;
    }

    synchronized T clear() {
        T previous = current;
        current = null;
        return previous;
    }
}
