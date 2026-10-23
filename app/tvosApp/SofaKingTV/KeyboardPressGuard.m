#import <UIKit/UIKit.h>
#import <objc/runtime.h>

// tvOS's keyboard can end up with no key under its cursor; a remote arrow pressed then throws NSRangeException
// (index NSNotFound) inside -[UIKeyboardLayoutCursor handleVisualDirectionalInput:], which UITextField's
// pressesBegan reaches through -[UIKeyboard canHandlePresses:withEvent:], and the app stops. Swift cannot catch an
// Objective-C exception, so this wraps UITextField's pressesBegan: that exception drops the press and closes the
// keyboard, whose state is then not to be trusted, and the field keeps what was typed.
@implementation UITextField (SofaKingKeyboardPressGuard)

+ (void)load {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Method original = class_getInstanceMethod(self, @selector(pressesBegan:withEvent:));
        Method guarded = class_getInstanceMethod(self, @selector(sofaking_pressesBegan:withEvent:));
        if (original && guarded) method_exchangeImplementations(original, guarded);
    });
}

- (void)sofaking_pressesBegan:(NSSet<UIPress *> *)presses withEvent:(UIPressesEvent *)event {
    @try {
        // Swapped at load, so this calls UIKit's own pressesBegan.
        [self sofaking_pressesBegan:presses withEvent:event];
    } @catch (NSException *exception) {
        if (![exception.name isEqualToString:NSRangeException]) @throw;
        dispatch_async(dispatch_get_main_queue(), ^{ [self resignFirstResponder]; });
    }
}

@end
