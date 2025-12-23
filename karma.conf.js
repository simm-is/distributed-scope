// Karma configuration for distributed-scope browser integration tests
// These tests connect to a JVM server and test remote function invocation

module.exports = function (config) {
  config.set({
    browsers: ['ChromeHeadless'],

    // The shadow-cljs :browser-ci build outputs karma-compatible test file
    frameworks: ['cljs-test'],

    files: [
      'target/browser-test/test.js'
    ],

    plugins: [
      'karma-cljs-test',
      'karma-chrome-launcher'
    ],

    colors: true,

    logLevel: config.LOG_INFO,

    // Don't watch for changes in CI mode
    autoWatch: false,

    // Only run once in CI mode
    singleRun: true,

    // Integration tests need longer timeouts for WebSocket connections
    // and remote function invocations
    client: {
      args: ['shadow.test.karma.init'],
      captureConsole: true
    },

    // Browser timeout for slow startup
    browserNoActivityTimeout: 180000,  // 3 minutes
    browserDisconnectTimeout: 60000,   // 1 minute
    captureTimeout: 120000,            // 2 minutes

    // Reporter configuration
    reporters: ['progress']
  });
};
