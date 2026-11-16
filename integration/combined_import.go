package core

// Compile both engines in one module before generating a single gomobile AAR.
// This gate detects incompatible shared dependencies before UI integration.
import _ "github.com/Cheems-gta/Cheems-Core/native/quic/mobilebridge"
