package me.aleksilassila.litematica.printer.handler.scan;

public enum ScanIntent {
    PRINT {
        @Override
        public boolean shouldConsider(byte flags) {
            return ScanFlags.has(flags, ScanFlags.SCHEMATIC_NON_AIR)
                    || ScanFlags.has(flags, ScanFlags.WORLD_NON_AIR);
        }

        @Override
        public boolean acceptsByFlags(byte flags) {
            return this.shouldConsider(flags);
        }
    },
    MINE {
        @Override
        public boolean shouldConsider(byte flags) {
            return ScanFlags.has(flags, ScanFlags.WORLD_NON_AIR)
                    && !ScanFlags.has(flags, ScanFlags.WORLD_FLUID);
        }
    },
    /** Bedrock planning needs the same non-fluid world candidates as mining. */
    BEDROCK {
        @Override
        public boolean shouldConsider(byte flags) {
            return ScanFlags.has(flags, ScanFlags.WORLD_NON_AIR)
                    && !ScanFlags.has(flags, ScanFlags.WORLD_FLUID);
        }

        @Override
        public boolean acceptsByFlags(byte flags) {
            return this.shouldConsider(flags);
        }
    },
    FLUID {
        @Override
        public boolean shouldConsider(byte flags) {
            return ScanFlags.has(flags, ScanFlags.WORLD_FLUID);
        }
    },
    TRENCH {
        @Override
        public boolean shouldConsider(byte flags) {
            return ScanFlags.has(flags, ScanFlags.WORLD_NON_AIR);
        }
    },
    FILL {
        @Override
        public boolean shouldConsider(byte flags) {
            return ScanFlags.has(flags, ScanFlags.BASE_FILL_TARGET);
        }

        @Override
        public boolean shouldRunExactPredicate(byte flags) {
            return this.shouldConsider(flags);
        }
    },
    CUSTOM {
        @Override
        public boolean shouldConsider(byte flags) {
            return true;
        }
    };

    public abstract boolean shouldConsider(byte flags);

    public boolean acceptsByFlags(byte flags) {
        return false;
    }

    public boolean shouldRunExactPredicate(byte flags) {
        return true;
    }
}
